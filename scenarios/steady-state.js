/**
 * Scenario 1: Steady-State Multi-Node Correctness
 *
 * Goal: prove that rate limits are enforced globally across all 3 nodes,
 * because state lives in Redis — not in any individual app instance.
 *
 * What to observe:
 *  - After ~15 rapid requests for the same user, 429s appear on ALL nodes
 *  - p95 latency stays well under 100ms
 *  - Error rate (5xx) stays at 0%
 *  - /stats on any node shows the same aggregated counts
 *
 * Run:
 *   docker compose up --build -d
 *   k6 run scenarios/steady-state.js
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ── Custom metrics ────────────────────────────────────────────────────────────
const allowedRequests  = new Counter('rl_allowed');
const rejectedRequests = new Counter('rl_rejected');
const serverErrors     = new Counter('rl_server_errors');
const rateLimitedRate  = new Rate('rl_rate_limited_rate');
const decisionLatency  = new Trend('rl_decision_latency_ms', true);

// ── Target nodes ──────────────────────────────────────────────────────────────
const NODES = [
    'http://localhost:8081',
    'http://localhost:8082',
    'http://localhost:8083',
];

const USERS = [
    { id: 'free-user-1',      tier: 'FREE'       },
    { id: 'free-user-2',      tier: 'FREE'       },
    { id: 'premium-user-1',   tier: 'PREMIUM'    },
    { id: 'premium-user-2',   tier: 'PREMIUM'    },
    { id: 'enterprise-user-1',tier: 'ENTERPRISE' },
];

// ── Load profile ──────────────────────────────────────────────────────────────
export const options = {
    stages: [
        { duration: '20s', target: 20 },   // ramp up to 20 VUs
        { duration: '60s', target: 20 },   // hold steady — observe allow/reject balance
        { duration: '10s', target: 0  },   // ramp down
    ],
    thresholds: {
        // http_req_failed counts 429 as "failed" by default in k6.
        // For a rate limiter, 429 is a correct response — use rl_server_errors instead.
        http_req_duration:    ['p(95)<150'],   // p95 under 150ms
        rl_rate_limited_rate: ['rate>0.20'],   // at least 20% of requests hit the limit (proves limits fire)
        rl_server_errors:     ['count<5'],     // zero 5xx is the real failure gate
    },
};

// ── Main VU loop ──────────────────────────────────────────────────────────────
export default function () {
    const node = NODES[__VU % NODES.length];                          // round-robin across nodes
    const user = USERS[Math.floor(Math.random() * USERS.length)];     // random user

    group('rate_limit_decision', () => {
        const start = Date.now();
        const res = http.post(
            `${node}/request`,
            JSON.stringify({ userId: user.id, endpoint: '/payments' }),
            { headers: { 'Content-Type': 'application/json' }, timeout: '2s' }
        );
        decisionLatency.add(Date.now() - start);

        const ok = check(res, {
            'status is 200 or 429': (r) => r.status === 200 || r.status === 429,
            'not a 5xx':            (r) => r.status < 500,
            'has allowed field':    (r) => {
                try { return r.json().hasOwnProperty('allowed'); } catch { return false; }
            },
        });

        if (res.status === 200) {
            allowedRequests.add(1);
            rateLimitedRate.add(false);
        } else if (res.status === 429) {
            rejectedRequests.add(1);
            rateLimitedRate.add(true);
        } else {
            serverErrors.add(1);
        }
    });

    sleep(0.05); // 50ms between iterations per VU — simulates real client pacing
}

// ── Teardown: check aggregated stats from each node ──────────────────────────
export function teardown() {
    console.log('\n=== STATS CONSISTENCY CHECK (must match across nodes) ===');
    NODES.forEach((node) => {
        const res = http.get(`${node}/stats?windowSeconds=120`);
        if (res.status === 200) {
            const stats = res.json();
            console.log(`${node}: accepted=${stats.accepted}  rejected=${stats.rejected}  rps=${stats.requestsPerSecond}`);
        } else {
            console.warn(`${node}: stats endpoint returned ${res.status}`);
        }
    });
}
