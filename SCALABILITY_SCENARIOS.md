# Scalability Scenarios — Runbook

This document covers every test you can run against the rate-limiter service to validate
scalability and resilience. It is organized into two parts:

- **Part A — k6 scenario tests**: real HTTP traffic against real running nodes, with fault injection
- **Part B — JMH microbenchmarks**: in-process throughput measurement of algorithms and routing

---

## Prerequisites

| Tool | Install | Version used |
|------|---------|--------------|
| Docker + Docker Compose | [docker.com](https://docker.com) | Docker 24+ |
| k6 | `winget install k6` (Windows) or `brew install k6` (Mac) | v0.50+ |
| Java 21 | Toolchain in `build.gradle` | 21 |
| curl / jq | Optional, for manual spot-checks | any |

---

## Stack startup

Always start from a clean state:

```bash
# Build the image and start all 3 app nodes + Redis Cluster (6 nodes)
docker compose up --build -d

# Wait for all services to be healthy.
# Cluster formation takes ~30-40s: nodes 1-5 start, then node-0 runs
# `redis-cli --cluster create` and waits for cluster_state:ok.
docker compose ps

# Verify each app node individually
curl -s http://localhost:8081/actuator/health | jq .status
curl -s http://localhost:8082/actuator/health | jq .status
curl -s http://localhost:8083/actuator/health | jq .status
```

Expected output from each health check:
```json
"UP"
```

To inspect the cluster topology at any time:
```bash
docker compose exec redis-node-0 redis-cli -p 6379 cluster nodes
```

Expected (3 masters, 3 replicas, all `connected`):
```
<id> redis-node-0:6379 myself,master - ...  0-5460
<id> redis-node-1:6379 master - ...         5461-10922
<id> redis-node-2:6379 master - ...         10923-16383
<id> redis-node-3:6379 slave <node-0-id> ...
<id> redis-node-4:6379 slave <node-1-id> ...
<id> redis-node-5:6379 slave <node-2-id> ...
```

---

## Part A — k6 Scenario Tests

### Scenario 1: Steady-State Multi-Node Correctness

**What it proves:** Rate limits are enforced globally. The same user hitting three different nodes
is limited by the shared Redis state, not per-node counters.

**Rate limits in effect (FREE tier):**
- 10 req/s with burst of 15 (Token Bucket)
- 100 req/min (Sliding Window Counter)

**Run:**

```bash
k6 run scenarios/steady-state.js
```

**What happens:**

1. k6 ramps to 20 virtual users (VUs) over 20 seconds
2. Each VU sends `POST /request` to a different node in round-robin (`8081 → 8082 → 8083 → 8081 ...`)
3. After 90s total the run ends and teardown prints stats from each node

**Expected terminal output (live):**

```
          /\      |‾‾| /‾‾/   /‾‾/
     /\  /  \     |  |/  /   /  /
    /  \/    \    |     (   /   ‾‾\
   /          \   |  |\  \ |  (‾)  |
  / __________ \  |__| \__\ \_____/ .io

  execution: local
     script: scenarios/steady-state.js
     output: -

  scenarios: (100.00%) 1 scenario, 20 max VUs, 2m00s max duration

running (0m20s), 20/20 VUs, 1230 complete and 0 interrupted iterations
rl_allowed..............: 873    14.55/s
rl_rejected.............: 357    5.95/s
rl_decision_latency_ms..: avg=8.2  min=2.1  med=6.4  max=89.4  p(90)=18.3  p(95)=26.1
```

**Teardown stats consistency check (printed at end):**

```
=== STATS CONSISTENCY CHECK (must match across nodes) ===
http://localhost:8081: accepted=1240  rejected=503  rps=14.2
http://localhost:8082: accepted=1240  rejected=503  rps=14.2
http://localhost:8083: accepted=1240  rejected=503  rps=14.2
```

All three nodes show the **same accepted/rejected totals** — because they read from the same
Redis analytics store. This is the correctness proof.

**Thresholds (will PASS green):**

```
✓ http_req_duration p(95)...: ~13ms       < 150ms
✓ rl_rate_limited_rate......: rate=64%+   > 20%   (limits are actively firing)
✓ rl_server_errors..........: count=0     < 5
```

> Note: k6's built-in `http_req_failed` counts `429 Too Many Requests` as a failure.
> For a rate limiter this is incorrect — `429` is a correct enforced response, not an error.
> The scripts use `rl_server_errors` (a custom counter) as the real failure gate instead.

---

### Scenario 2: Node Crash Mid-Traffic

**What it proves:** When one app node dies, Redis state survives untouched. The two remaining
nodes keep enforcing limits correctly. The fail-open policy applies only to Redis outage — a dead
*app* node is simply unreachable, not a reason to open the circuit.

**Run:**

Open two terminals.

**Terminal 1 — start load test:**
```bash
k6 run scenarios/node-crash.js
```

**Terminal 2 — inject fault at ~T=30s (when you see "30s" in k6 output):**
```bash
docker compose stop app-2
```

**Terminal 2 — recover at ~T=90s:**
```bash
docker compose start app-2
```

**What happens phase by phase:**

| Phase | Time | What k6 sees |
|-------|------|--------------|
| Phase 1 | 0–30s | All 3 nodes healthy. Normal 200/429 mix. `rl_connection_errors=0` |
| Phase 2 | 30–90s | `app-2` dead. VUs assigned to port 8082 get `connection refused`. `rl_connection_errors` spikes. VUs on 8081/8083 stay clean |
| Phase 3 | 90–120s | `app-2` restarts. It rejoins the consumer group. Error rate drops to 0% |

**Expected terminal output during phase 2 (crash window):**

```
WARN [T=1713800045s] http://localhost:8082 unreachable: dial: connection refused
WARN [T=1713800046s] http://localhost:8082 unreachable: dial: connection refused
...
rl_connection_errors...: 387     6.45/s    ← ~33% of VUs, expected
rl_allowed.............: 521     8.68/s
rl_rejected............: 210     3.50/s
```

**Teardown health check (printed at end):**

```
=== POST-CRASH HEALTH CHECK ===
http://localhost:8081: HTTP 200
  accepted=1842  rejected=634
http://localhost:8082: HTTP 200
  accepted=1842  rejected=634    ← matches others — Redis state was never lost
http://localhost:8083: HTTP 200
  accepted=1842  rejected=634
```

**Thresholds:**

```
✓ rl_error_rate......: rate=32.1%  < 40%   (33% expected from dead node)
✓ http_req_duration..: p(95)=44ms  < 200ms (surviving nodes unaffected)
```

**What to look for in Redis after the test:**

```bash
# Check that stream consumer group still has all 3 consumers registered
# -c flag enables cluster mode (follows MOVED redirects automatically)
docker compose exec redis-node-0 redis-cli -c XINFO GROUPS rate-limit-events

# Check pending messages (should be 0 or near 0 after app-2 restarts
# and processes its backlog via reclaimPending())
docker compose exec redis-node-0 redis-cli -c XPENDING rate-limit-events analytics - + 10
```

Expected after recovery:
```
(empty list or set)
```

---

### Scenario 3: Redis Outage — Circuit Breaker + Fail-Open

**What it proves:** When Redis is unavailable, the Resilience4j circuit breaker opens and
`allowOnFailure()` returns `true` for every request. No 5xx errors are returned to clients.
When Redis recovers, the circuit closes and normal rate limiting resumes.

**Run:**

Open two terminals.

**Terminal 1 — start load test:**
```bash
k6 run scenarios/redis-outage.js
```

**Terminal 2 — stop all Redis masters at ~T=30s:**
```bash
docker compose stop redis-node-0 redis-node-1 redis-node-2
```

**Terminal 2 — restart masters at ~T=90s:**
```bash
docker compose start redis-node-0 redis-node-1 redis-node-2
```

**What happens phase by phase:**

| Phase | Time | What k6 sees | Circuit breaker state |
|-------|------|--------------|----------------------|
| Phase 1 | 0–30s | Normal 200/429 mix | CLOSED |
| Phase 2 | 30–90s | **Only 200s** — no 429s. `rl_fail_open` increments | OPEN |
| Phase 3 | 90–120s | 429s reappear within ~5s of Redis restart | HALF-OPEN → CLOSED |

**Expected terminal output during phase 2 (outage window):**

```
# Notice: rl_rejected STOPS counting at T=30s
rl_allowed.............: 2400    40.0/s   ← everything allowed (fail-open)
rl_rejected............: 0       0.0/s    ← no 429s — circuit is open
rl_server_errors.......: 0       0.0/s    ← zero 5xx — this is the key assertion
```

**Check circuit breaker state mid-outage:**

```bash
curl -s http://localhost:8081/actuator/health | jq .
```

Expected during Redis outage:
```json
{
  "status": "DOWN",
  "components": {
    "redis": { "status": "DOWN" },
    "circuitBreakers": {
      "status": "CIRCUIT_OPEN",
      "details": {
        "redisRateLimiter": {
          "details": { "state": "OPEN" }
        }
      }
    }
  }
}
```

**Check Prometheus metrics mid-outage:**

```bash
curl -s http://localhost:8081/actuator/prometheus | grep resilience4j_circuitbreaker_state
```

Expected:
```
resilience4j_circuitbreaker_state{name="redisRateLimiter",state="closed",...} 0.0
resilience4j_circuitbreaker_state{name="redisRateLimiter",state="open",...}   1.0
```

**Teardown — verify circuit is closed and rate limiting resumed:**

```
=== CIRCUIT BREAKER STATE CHECK ===
http://localhost:8081: status=UP
  circuitBreaker.state=CLOSED
http://localhost:8082: status=UP
  circuitBreaker.state=CLOSED
http://localhost:8083: status=UP
  circuitBreaker.state=CLOSED

=== REJECT RATE AFTER RECOVERY ===
20 rapid requests: allowed=10  rejected=10
✓ Circuit is CLOSED — rate limiting is active again
```

**Thresholds:**

```
✓ rl_server_error_rate: rate=0.00%  < 1%   (no 5xx at any point)
✓ http_req_failed......: rate=0.00%  < 1%
✓ http_req_duration....: p(95)=91ms  < 500ms (wider to allow Redis restart spike)
```

---

### Scenario 4: Redis Cluster Master Failover

**What it proves:** When one Redis cluster master is killed, the replica is promoted (~1-5 s
election), Lettuce's adaptive topology refresh reconnects the app, and rate limiting resumes.
No 5xx errors reach clients — the circuit breaker absorbs the brief election window via
fail-open.

**Cluster topology (default):**

| Role    | Node           | Serves slots  |
|---------|----------------|---------------|
| Master  | redis-node-0   | 0 – 5460      |
| Master  | redis-node-1   | 5461 – 10922  |
| Master  | redis-node-2   | 10923 – 16383 |
| Replica | redis-node-3   | replica of 0  |
| Replica | redis-node-4   | replica of 1  |
| Replica | redis-node-5   | replica of 2  |

**Run:**

Open two terminals.

**Terminal 1 — start load test:**
```bash
k6 run scenarios/cluster-failover.js
```

**Terminal 2 — kill master at ~T=30s (when you see "30s" in k6 output):**
```bash
docker compose stop redis-node-1
```

**Terminal 2 — restart at ~T=90s:**
```bash
docker compose start redis-node-1
```

**What happens phase by phase:**

| Phase | Time   | What k6 sees | Cluster state |
|-------|--------|--------------|---------------|
| Phase 1 | 0–30s  | Normal 200/429 mix. `rl_failover_errors=0` | All 6 nodes connected |
| Phase 2 | 30–90s | `rl_failover_errors` spikes briefly (~5-10 s) during election. After redis-node-4 is promoted and topology refreshes, 429s resume | redis-node-1 dead → redis-node-4 elected master |
| Phase 3 | 90–120s | Zero disruption. redis-node-1 rejoins as a replica | Cluster fully restored |

**Expected terminal output during the election window (~5 s spike at T=30s):**

```
[sample] http://localhost:8081 transport error during failover: dial: ...
rl_failover_errors...: 8       0.13/s    ← brief burst, stops after election
rl_allowed...........: 840     14.0/s
rl_rejected..........: 320     5.3/s
rl_server_errors.....: 0       0.0/s     ← zero 5xx throughout
```

**Teardown output (printed at end):**

```
=== CLUSTER RECOVERY CHECK ===
http://localhost:8081: status=UP
  redis.status=UP
http://localhost:8082: status=UP
  redis.status=UP
http://localhost:8083: status=UP
  redis.status=UP

=== RATE LIMIT ENFORCEMENT CHECK (must see 429s) ===
20 rapid requests: allowed=10  rejected=10
✓ Rate limits enforced — cluster fully recovered

=== STATS CONSISTENCY CHECK (accepted + rejected must match across nodes) ===
http://localhost:8081: accepted=1412  rejected=531  rps=12.8
http://localhost:8082: accepted=1412  rejected=531  rps=12.8
http://localhost:8083: accepted=1412  rejected=531  rps=12.8
```

**Thresholds:**

```
✓ rl_server_error_rate: rate=0.00%  < 1%    (no 5xx at any point)
✓ rl_failover_errors...: count=8    < 30    (brief election window only)
✓ http_req_duration....: p(95)=72ms < 350ms (spike at election, recovers fast)
```

**Verify cluster state after the test:**

```bash
# Confirm redis-node-4 is now a master and redis-node-1 is a replica
docker compose exec redis-node-0 redis-cli -p 6379 cluster nodes

# Confirm stream group survived the failover (stream key may have moved shards)
docker compose exec redis-node-0 redis-cli -c XINFO GROUPS rate-limit-events
```

---

## Part B — JMH Microbenchmarks

JMH measures in-process algorithm throughput — no network, no Redis. Run these to confirm
the hot code paths are never the bottleneck.

### Run all benchmarks

```bash
./gradlew jmh
```

This runs both `AlgorithmBenchmark` and `RoutingBenchmark` with 3 warmup + 5 measurement
iterations each.

### Run only the routing benchmark

```bash
./gradlew jmh --args='-f 1 -wi 3 -i 5 -rff results.json -rf json RoutingBenchmark'
```

### Run only the algorithm benchmark

```bash
./gradlew jmh --args='-f 1 -wi 3 -i 5 AlgorithmBenchmark'
```

---

### RoutingBenchmark — expected results

The benchmark runs `ConsistentHashRing.route()` at 1, 8, and 32 threads, for both 3-node
and 5-node rings, with 50 and 150 virtual nodes each.

**Expected output:**

```
Benchmark                              (nodeCount)  (virtualNodes)   Mode  Cnt        Score   Error  Units
RoutingBenchmark.routeSingleThread               3              50  thrpt    5  24,183,442 ± 312,441  ops/s
RoutingBenchmark.routeSingleThread               3             150  thrpt    5  21,947,118 ± 284,302  ops/s
RoutingBenchmark.routeSingleThread               5              50  thrpt    5  23,802,091 ± 298,112  ops/s
RoutingBenchmark.routeEightThreads               3              50  thrpt    5  89,241,830 ± 1,204,321  ops/s
RoutingBenchmark.routeEightThreads               3             150  thrpt    5  82,103,447 ± 981,022  ops/s
RoutingBenchmark.routeThirtyTwoThreads           3              50  thrpt    5  104,382,019 ± 2,341,002  ops/s
RoutingBenchmark.routeThirtyTwoThreads           3             150  thrpt    5  98,104,301 ± 1,892,441  ops/s
```

**How to read the results:**

| What you see | What it means |
|---|---|
| 24M ops/s single thread | Routing adds ~41ns per request — never a bottleneck |
| 89M ops/s at 8 threads | Near-linear scaling — `TreeMap` read path has no contention |
| 104M ops/s at 32 threads | Scales past 8T — OS thread scheduling helps here |
| 3-node vs 5-node: ~same | Node count has negligible impact on routing latency |
| 50 vs 150 virtual nodes: ~8% slower | More virtual nodes = larger TreeMap = slightly slower `ceilingEntry()` |

**Conclusion:** routing overhead is `~40ns` per request. At 20,000 req/s per node (a
realistic peak), routing consumes `0.8ms` of CPU per second — effectively zero.

---

### AlgorithmBenchmark — expected results

```
Benchmark                                    Mode  Cnt         Score       Error  Units
AlgorithmBenchmark.tokenBucket               thrpt    5  18,432,110 ±  241,002  ops/s
AlgorithmBenchmark.slidingWindowCounter      thrpt    5  16,893,204 ±  198,441  ops/s
```

Both algorithms at 8 threads sustain **16–18 million decisions per second**.

At 20,000 req/s load, the algorithm layer uses `20,000 / 18,000,000 = 0.1%` of a single CPU core.
The bottleneck is always the Redis round-trip (`~1ms`), not the algorithm.

---

## Combining both tools: the full picture

```
                  ┌─────────────────────────────────────┐
                  │         What you can prove           │
 ┌────────────┐   ├──────────────────┬──────────────────┤
 │ k6 scripts │   │ limits enforced  │ circuit breaker  │
 │ (real HTTP)│──▶│ globally across  │ opens on Redis   │
 └────────────┘   │ nodes            │ outage           │
                  ├──────────────────┼──────────────────┤
 ┌────────────┐   │ algorithm never  │ routing never    │
 │    JMH     │──▶│ a bottleneck     │ a bottleneck     │
 │ (in-proc)  │   │ (18M ops/sec)    │ (24M ops/sec)    │
 └────────────┘   └──────────────────┴──────────────────┘
```

k6 answers: *does the system behave correctly under real faults?*
JMH answers: *are the hot code paths fast enough to never be the bottleneck?*

---

## Teardown

```bash
docker compose down -v   # stops all containers and removes any anonymous volumes
```
