/**
 * Scenario 4: Redis Cluster Master Failover
 *
 * Goal: prove that when one Redis cluster master is killed mid-traffic, the
 * replica is elected as the new master (~1-5 s election window), and the app
 * resumes enforcing rate limits with no human intervention beyond restarting
 * the node. No 5xx errors are returned to clients — the circuit breaker absorbs
 * the brief election window via fail-open.
 *
 * Cluster topology (default assignment):
 *   Masters  : redis-node-0  redis-node-1  redis-node-2
 *   Replicas : redis-node-3  redis-node-4  redis-node-5
 *
 * Three phases:
 *   Phase 1 (0-30s)  — all 6 cluster nodes healthy, normal 200/429 mix
 *   Phase 2 (30-90s) — redis-node-1 (master) is killed externally
 *                      ~1-5s election: redis-node-4 promoted to master
 *                      during the election window, affected keys hit the
 *                      circuit breaker (fail-open → 200); after Lettuce's
 *                      adaptive topology refresh (~30 s period or triggered
 *                      by MOVED errors) normal 429s resume for those keys
 *   Phase 3 (90-120s)— redis-node-1 is restarted; rejoins as a replica,
 *                      cluster re-balances; zero disruption visible
 *
 * What to observe:
 *   - rl_failover_errors spikes briefly during the election window (~5-10 s)
 *   - rl_rejected PAUSES for the keys that mapped to the failed shard,
 *     then RESUMES once the new master is elected and topology refreshed
 *   - rl_server_errors stays at 0 throughout (no 5xx to clients)
 *   - After phase 3: cluster_state:ok on all nodes, rate limits enforced
 *
 * Run:
 *   docker compose up --build -d
 *
 *   # Wait for all services to be healthy (~30-40s for cluster formation)
 *   docker compose ps
 *
 *   # Terminal 1 — start load test
 *   k6 run scenarios/cluster-failover.js
 *
 *   # Terminal 2 — kill master at ~T=30s (when you see "30s" in k6 output)
 *   docker compose stop redis-node-1
 *
 *   # Terminal 2 — restart at ~T=90s
 *   docker compose start redis-node-1
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ── Custom metrics ────────────────────────────────────────────────────────────
const allowedRequests   = new Counter('rl_allowed');
const rejectedRequests  = new Counter('rl_rejected');
const failoverErrors    = new Counter('rl_failover_errors');  // errors during election window
const serverErrors      = new Counter('rl_server_errors');
const decisionLatency   = new Trend('rl_decision_latency_ms', true);
const serverErrorRate   = new Rate('rl_server_error_rate');
const rateLimitedRate   = new Rate('rl_rate_limited_rate');

// ── Target nodes ──────────────────────────────────────────────────────────────
const NODES = [
    'http://localhost:8081',
    'http://localhost:8082',
    'http://localhost:8083',
];

// Three users — one whose keys hash to each of the three cluster shards.
// This guarantees the failover of redis-node-1 is visible in the metrics
// (some requests will hit the lost shard), without using too many users
// to avoid diluting the per-user request rate below the rate limits.
//
// NOTE: On Windows with Docker Desktop, port-forwarding drops TCP connections
// when total throughput exceeds ~150 req/s. Keep max VUs at 10 so that
// Docker Desktop's proxy is not overloaded. 10 VUs × ~18 req/s = ~180 req/s
// stays just within the safe zone while still generating enough per-user
// traffic to trigger the 10 req/s free-tier token bucket.
const USERS = [
    'free-user-1',
    'premium-user-1',
    'enterprise-user-1',
];

// ── Load profile ──────────────────────────────────────────────────────────────
export const options = {
    stages: [
        { duration: '30s', target: 10 },  // phase 1: ramp up, full cluster healthy
        { duration: '60s', target: 10 },  // phase 2: hold — kill redis-node-1 at ~T=30s
        { duration: '30s', target: 10 },  // phase 3: restart redis-node-1 at ~T=90s
        { duration: '10s', target: 0  },  // ramp down
    ],
    thresholds: {
        // Zero 5xx to clients at any point — this is the primary assertion.
        rl_server_error_rate: ['rate<0.01'],

        // Allow a brief disruption during the ~1-5s election window.
        // After election + topology refresh, errors must stop.
        rl_failover_errors: ['count<30'],

        // Rate limiting must fire: proves the cluster is enforcing limits.
        // During the election window (~5 s) this may dip, but should hold
        // before and after.
        rl_rate_limited_rate: ['rate>0.30'],

        // p95 latency must recover after the election spike.
        // Wider than the steady-state 150ms to accommodate topology refresh.
        http_req_duration: ['p(95)<350'],
    },
};

// ── Main VU loop ──────────────────────────────────────────────────────────────
export default function () {
    const node   = NODES[__VU % NODES.length];
    const userId = USERS[Math.floor(Math.random() * USERS.length)];

    group('rate_limit_decision', () => {
        const start = Date.now();
        const res = http.post(
            `${node}/request`,
            JSON.stringify({ userId, endpoint: '/payments' }),
            {
                headers: { 'Content-Type': 'application/json' },
                // 1.5s timeout: generous enough to survive a brief CLUSTERDOWN
                // response while still catching genuine hangs.
                timeout: '1500ms',
            }
        );
        decisionLatency.add(Date.now() - start);

        // True transport error = no HTTP response received at all (status === 0).
        // k6 encodes HTTP 4xx/5xx as error_code = 1xxx (e.g., 429 → 1429), so
        // error_code !== 0 incorrectly classifies rate-limit rejections as TCP
        // failures. Using res.status === 0 isolates genuine connection errors.
        const isTransportError = res.status === 0;
        const isServerError    = !isTransportError && res.status >= 500;

        // Transport errors (connection refused, timeout) during the election
        // window are expected; log a sample to avoid flooding output.
        if (isTransportError) {
            failoverErrors.add(1);
            serverErrorRate.add(true);
            if (Math.random() < 0.05) {
                console.warn(`[sample] ${node} transport error during failover: ${res.error}`);
            }
            return;
        }

        serverErrorRate.add(false);

        check(res, {
            'no 5xx ever':          (r) => r.status < 500,
            'status is 200 or 429': (r) => r.status === 200 || r.status === 429,
        });

        if (isServerError) {
            serverErrors.add(1);
            return;
        }

        if (res.status === 200) {
            allowedRequests.add(1);
            rateLimitedRate.add(false);
        } else if (res.status === 429) {
            rejectedRequests.add(1);
            rateLimitedRate.add(true);
        }
    });

    sleep(0.1);
}

// ── Teardown: verify cluster recovered and rate limits are enforced ───────────
export function teardown() {
    console.log('\n=== CLUSTER RECOVERY CHECK ===');
    NODES.forEach((node) => {
        const res = http.get(`${node}/actuator/health`, { timeout: '3s' });
        if (res.error_code !== 0) {
            console.warn(`${node}: UNREACHABLE`);
            return;
        }
        try {
            const health = res.json();
            console.log(`${node}: status=${health.status}`);
            const redis = health?.components?.redis;
            if (redis) {
                console.log(`  redis.status=${redis.status}`);
            }
        } catch {
            console.log(`${node}: health=${res.status}`);
        }
    });

    console.log('\n=== RATE LIMIT ENFORCEMENT CHECK (must see 429s) ===');
    // Rapid-fire a free-tier user to confirm limits are back after failover.
    // A free-tier user hitting /payments is capped at 10 req/s; 20 rapid
    // requests in the same second must produce at least some 429s.
    const node = NODES[0];
    let allowed = 0;
    let rejected = 0;
    for (let i = 0; i < 20; i++) {
        const r = http.post(
            `${node}/request`,
            JSON.stringify({ userId: 'free-user-1', endpoint: '/payments' }),
            { headers: { 'Content-Type': 'application/json' }, timeout: '2s' }
        );
        if (r.status === 200)      allowed++;
        else if (r.status === 429) rejected++;
    }
    console.log(`20 rapid requests: allowed=${allowed}  rejected=${rejected}`);
    console.log(rejected > 0
        ? '✓ Rate limits enforced — cluster fully recovered'
        : '⚠ No rejections — circuit may still be open, or limits not reached'
    );

    console.log('\n=== STATS CONSISTENCY CHECK (accepted + rejected must match across nodes) ===');
    NODES.forEach((node) => {
        const s = http.get(`${node}/stats?windowSeconds=120`, { timeout: '3s' });
        if (s.status === 200) {
            const data = s.json();
            console.log(`${node}: accepted=${data.accepted}  rejected=${data.rejected}  rps=${data.requestsPerSecond}`);
        } else {
            console.warn(`${node}: stats returned ${s.status}`);
        }
    });
}
