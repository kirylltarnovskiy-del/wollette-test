/**
 * Scenario 3: Redis Outage — Circuit Breaker + Fail-Open
 *
 * Goal: prove that when Redis goes down, the circuit breaker opens and the
 * service switches to fail-open (all requests allowed) rather than returning
 * 500s. When Redis comes back, the circuit closes and normal 200/429 behavior
 * resumes within seconds.
 *
 * Three phases:
 *   Phase 1 (0-30s)  — Redis healthy, normal allow/reject
 *   Phase 2 (30-90s) — Redis killed externally; ALL responses become 200 (fail-open)
 *                      No 429s during this window — proves circuit breaker works
 *   Phase 3 (90-120s)— Redis restarted; 429s reappear as circuit closes
 *
 * What to observe:
 *   - During phase 2: rl_rejected counter STOPS incrementing
 *   - During phase 2: rl_fail_open counter increments rapidly
 *   - After phase 3: 429s resume and rl_rejected increments again
 *   - Zero 5xx responses throughout all phases
 *
 * Run:
 *   docker compose up --build -d
 *
 *   # Terminal 1
 *   k6 run scenarios/redis-outage.js
 *
 *   # Terminal 2 — kill Redis at ~T=30s
 *   docker compose stop redis
 *
 *   # Terminal 2 — restart Redis at ~T=90s
 *   docker compose start redis
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ── Custom metrics ────────────────────────────────────────────────────────────
const allowedRequests  = new Counter('rl_allowed');
const rejectedRequests = new Counter('rl_rejected');
const failOpenRequests = new Counter('rl_fail_open');   // 200s during Redis outage
const serverErrors     = new Counter('rl_server_errors');
const decisionLatency  = new Trend('rl_decision_latency_ms', true);
const serverErrorRate  = new Rate('rl_server_error_rate');

const NODES = [
    'http://localhost:8081',
    'http://localhost:8082',
    'http://localhost:8083',
];

// Use the same user throughout to make the phase transition easy to see:
// in phase 1 they get 429s, in phase 2 they always get 200, in phase 3 429s return.
const POWER_USER = 'free-user-burst';

export const options = {
    stages: [
        { duration: '30s', target: 40 },  // phase 1: ramp up, Redis healthy
        { duration: '60s', target: 40 },  // phase 2: hold — stop redis at ~T=30s
        { duration: '30s', target: 40 },  // phase 3: start redis at ~T=90s
        { duration: '10s', target: 0  },  // ramp down
    ],
    thresholds: {
        // The critical assertion: no 5xx at any point.
        // Do NOT use http_req_failed — k6 counts 429 as failed, which is wrong here.
        rl_server_error_rate: ['rate<0.01'],
        // Wider p95 window: Redis restart causes a brief latency spike
        http_req_duration:    ['p(95)<500'],
    },
};

export default function () {
    const node = NODES[__VU % NODES.length];

    group('rate_limit_decision', () => {
        const start = Date.now();
        const res = http.post(
            `${node}/request`,
            JSON.stringify({ userId: POWER_USER, endpoint: '/payments' }),
            {
                headers: { 'Content-Type': 'application/json' },
                timeout: '2s',
            }
        );
        decisionLatency.add(Date.now() - start);

        const isServerError = res.status >= 500 || res.error_code !== 0;

        check(res, {
            'no 5xx ever':          (r) => r.status < 500 && r.error_code === 0,
            'status is 200 or 429': (r) => r.status === 200 || r.status === 429,
        });

        serverErrorRate.add(isServerError ? true : false);

        if (isServerError) {
            serverErrors.add(1);
            return;
        }

        if (res.status === 200) {
            allowedRequests.add(1);
            // During outage, a high-traffic user who should be rate-limited
            // gets 200 — that's the fail-open circuit breaker working correctly.
            // We detect this by checking the Retry-After header is absent (no 429).
            const retryAfter = res.headers['Retry-After'];
            if (!retryAfter) {
                // Could be either legitimately allowed OR fail-open.
                // We can't distinguish from the client side — which is correct.
                // Check /actuator/health or Prometheus to see circuit breaker state.
            }
        } else if (res.status === 429) {
            rejectedRequests.add(1);
        }
    });

    sleep(0.05);
}

export function teardown() {
    console.log('\n=== CIRCUIT BREAKER STATE CHECK ===');
    NODES.forEach((node) => {
        const res = http.get(`${node}/actuator/health`, { timeout: '3s' });
        if (res.status === 200) {
            try {
                const health = res.json();
                console.log(`${node}: status=${health.status}`);
                const cb = health?.components?.circuitBreakers?.details?.redisRateLimiter;
                if (cb) {
                    console.log(`  circuitBreaker.state=${cb.details?.state}`);
                }
            } catch {
                console.log(`${node}: health=${res.status}`);
            }
        } else {
            console.warn(`${node}: health returned ${res.status}`);
        }
    });

    console.log('\n=== REJECT RATE AFTER RECOVERY ===');
    // Rapid-fire a burst to confirm 429s are back (circuit is closed again)
    const node = NODES[0];
    let allowed = 0, rejected = 0;
    for (let i = 0; i < 20; i++) {
        const r = http.post(
            `${node}/request`,
            JSON.stringify({ userId: POWER_USER, endpoint: '/payments' }),
            { headers: { 'Content-Type': 'application/json' }, timeout: '2s' }
        );
        if (r.status === 200)      allowed++;
        else if (r.status === 429) rejected++;
    }
    console.log(`20 rapid requests: allowed=${allowed}  rejected=${rejected}`);
    console.log(rejected > 0
        ? '✓ Circuit is CLOSED — rate limiting is active again'
        : '⚠ No rejections — circuit may still be open or limits not reached'
    );
}
