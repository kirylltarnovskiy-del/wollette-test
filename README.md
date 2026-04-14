# Production Rate Limiter + Analytics Service

High-performance distributed rate limiter with real-time analytics, implemented as a hexagonal-architecture Spring Boot service with a 6-node Redis Cluster backend.

## Architecture

The production deployment runs **three application nodes** backed by a **6-node Redis Cluster** (3 masters + 3 replicas):

```mermaid
flowchart TD
    Client -->|"POST /request\nGET /stats\nGET /users/{id}/usage"| LB[Load Balancer]
    LB --> app1[app-1 :8081]
    LB --> app2[app-2 :8082]
    LB --> app3[app-3 :8083]

    app1 & app2 & app3 -->|"Lua scripts\n(atomic check-and-update)"| RC[(Redis Cluster\n3 masters · 3 replicas)]
    app1 & app2 & app3 -->|XADD rate-limit-events| RC

    RC -->|XREADGROUP per instance| C1[Consumer app-1]
    RC -->|XREADGROUP per instance| C2[Consumer app-2]
    RC -->|XREADGROUP per instance| C3[Consumer app-3]

    C1 & C2 & C3 --> Agg[AnalyticsAggregator]
    Agg -->|ZINCRBY top-users\nHSET user:{id}| RC
```

### Internal call flow

```
POST /request
  → RateLimiterController
  → RateLimiterService.allowRequest(userId, endpoint)
      → PropertiesUserConfigAdapter.getTier(userId)           [tier by userId prefix]
      → For each rule in tier (all must pass):
          → RedisRateLimiterAdapter.allowTokenBucket()        [token_bucket.lua]
          OR RedisRateLimiterAdapter.allowSlidingWindowCounter() [sliding_window.lua]
      → RedisStreamEventPublisher.publish(event)              [XADD → async analytics]
  ← HTTP 200 {allowed: true}  or  HTTP 429 {allowed: false}
```

## Design Decisions

See **[DESIGN_DECISIONS.md](DESIGN_DECISIONS.md)** for the full write-up of every architectural choice, the alternatives considered, and the trade-offs involved.

Summary of key decisions:

| Area | Choice | Rationale |
|------|--------|-----------|
| Rate limit state | Redis Lua scripts (atomic) | Single check-and-update; sub-ms latency; shared across all app nodes |
| Algorithms | Token Bucket + Sliding Window Counter | Burst-tolerant and strict-fairness; orders-of-magnitude less memory than Sliding Window Log |
| Analytics delivery | Redis Streams (async) | At-least-once delivery; keeps `allowRequest` latency minimal; no extra broker |
| Failure policy | Fail-open on Redis unavailability | Resilience4j circuit breaker; availability over strictness during outages |
| Architecture | Ports and Adapters (Hexagonal) | Domain and application layers fully isolated from Spring and Redis |
| Multi-node | 3 app nodes + 6-node Redis Cluster | Global rate limit state; no per-node counter drift; built-in HA and sharding |

## Algorithm Choices

Two algorithms are implemented and compared. Both are assigned **per-rule** in configuration, allowing each tier to combine burst-tolerant short-window rules with strict long-window rules:

| Algorithm | Accuracy | Memory per user | Throughput (8 threads) | Burst support | Assigned to |
|-----------|----------|-----------------|------------------------|---------------|-------------|
| **Token Bucket** | Exact | 2 values (tokens + timestamp) | ~18 M ops/s | Yes (`burstCapacity`) | Per-second rules |
| **Sliding Window Counter** | ~99% | 2 counters + timestamp | ~17 M ops/s | No | Per-minute rules |

**Why not Sliding Window Log?** Memory grows linearly with request rate — one timestamp entry per request per window. At 1,000 req/s over 60 s that is 60,000 log entries per user. The ~1% approximation error of the counter is an acceptable trade-off for the order-of-magnitude memory reduction.

**Why not Leaky Bucket?** It enforces a strictly smooth output rate with no burst allowance. Token Bucket covers the same use case with configurable burst capacity, making Leaky Bucket redundant for user-facing API quotas.

## Trade-offs

| Decision | Benefit | Cost |
|----------|---------|------|
| Redis as the sole store | Global consistency; sub-ms latency | Redis is a required operational dependency |
| Fail-open on Redis outage | No traffic denial during Redis unavailability | Users can briefly exceed limits while circuit is open |
| Redis Streams over Kafka | Single infrastructure component to operate | Lower throughput ceiling (adequate for this workload) |
| Sliding Window Counter approximation | Very low memory; O(1) operations | ~1% overcounting at window boundaries |
| Consistent hash ring simulation | Fraction of users remap on node change | Node loss resets counters for affected users; brief burst possible |
| CAS loop concurrency | 3–10× higher throughput than synchronized | CPU spin under extreme contention |

## Bottlenecks

- **Redis round-trip (~1 ms):** The Lua script execution dominates latency. Algorithm computation is ~50 ns (see JMH results). Network to Redis is always the bottleneck.
- **Single stream key** (`rate-limit-events`): A single Redis Stream key lives in one cluster slot. At very high analytics volume this becomes a hot key. Mitigation: shard by `userId % N` into N stream keys.
- **In-memory ring per instance:** The `windowStats` part of `/stats` (per-second breakdown) is aggregated from the events consumed by that instance. In a multi-instance deployment each node reports only its share of the stream. The Redis ZSET-backed top-user rankings and overall accepted/rejected totals are global (shared Redis state).
- **Redis Streams throughput below Kafka:** Suitable for this service; Kafka would be needed only if analytics event volume exceeded ~100 k/s.

## Failure Modes

All three required failure modes are implemented:

| Failure | Simulation | System behaviour |
|---------|-----------|-----------------|
| **Node crash** | `docker compose stop app-2` | Remaining nodes keep serving; Redis state unchanged; no 5xx |
| **Redis outage** | `docker compose stop redis-node-0 redis-node-1 redis-node-2` | Circuit breaker opens; all requests allowed (fail-open); no 5xx |
| **Clock skew** | `RateLimiterNode.withClockOffset()` | Token Bucket is immune (monotonic time); Sliding Window Counter may briefly allow overcounting at window boundary |
| **Partial data loss** | `RateLimiterNode` state cleared | Affected users' counters reset; brief burst possible; recovers within one window |

See **[SCALABILITY_SCENARIOS.md](SCALABILITY_SCENARIOS.md)** for the full runbook with expected k6 output.

## Run Locally

### Prerequisites

- Java 21
- Docker + Docker Compose

### Option A: Full multi-node stack (recommended)

```bash
docker compose up --build -d
```

This starts:
- `redis-node-0` – `redis-node-5` — 6-node Redis Cluster (3 masters + 3 replicas)
- `redis-cluster-init` — one-shot container that bootstraps the cluster topology
- `app-1` on `localhost:8081`
- `app-2` on `localhost:8082`
- `app-3` on `localhost:8083`

Wait ~30–40 s for cluster formation, then verify:

```bash
curl -s http://localhost:8081/actuator/health | jq .status   # "UP"
curl -s http://localhost:8082/actuator/health | jq .status   # "UP"
curl -s http://localhost:8083/actuator/health | jq .status   # "UP"

# Inspect cluster topology (3 masters + 3 replicas, all "connected")
docker compose exec redis-node-0 redis-cli -p 6379 cluster nodes
```

### Option B: Single app node with standalone Redis (development)

```bash
# Start a standalone Redis container (not the cluster compose)
docker run -d --name redis-dev -p 6379:6379 redis:7.2-alpine

# Run the app (connects to localhost:6379 by default)
./gradlew bootRun
```

### Verify

```bash
# Allow a request (replace 8080 with 8081/8082/8083 for the multi-node stack)
curl -X POST localhost:8080/request \
  -H "Content-Type: application/json" \
  -d '{"userId":"free-user","endpoint":"/payments"}'

# Trigger rate limiting — fire 20 rapid requests; some will return 429
for i in $(seq 1 20); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/request \
    -H "Content-Type: application/json" \
    -d '{"userId":"free-user","endpoint":"/payments"}'
done

# Analytics
curl "localhost:8080/stats?windowSeconds=60"
curl localhost:8080/users/free-user/usage

# Health and Prometheus metrics
curl localhost:8080/actuator/health
curl localhost:8080/actuator/prometheus
```

## Run in Production

### 1) Build image

```bash
./gradlew clean bootJar
docker build -t rate-limiter-service:latest .
```

### 2) Required environment variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SPRING_DATA_REDIS_CLUSTER_NODES` | Yes (cluster) | — | Comma-separated `host:port` list for all Redis Cluster nodes |
| `RATE_LIMITER_STREAM_CONSUMER` | Recommended | `hostname` | Unique consumer ID per instance — use pod name in Kubernetes |
| `RATE_LIMITER_STREAM_GROUP` | No | `analytics` | Redis Streams consumer group name |
| `RATE_LIMITER_STREAM_BATCH_SIZE` | No | `100` | Events read per poll cycle |
| `JAVA_TOOL_OPTIONS` | No | — | JVM tuning flags |

> For a standalone Redis (single-node dev/test), use `REDIS_HOST` + `REDIS_PORT` instead of `SPRING_DATA_REDIS_CLUSTER_NODES`.

### 3) Run container

```bash
docker run --rm -p 8080:8080 \
  -e SPRING_DATA_REDIS_CLUSTER_NODES=redis-0:6379,redis-1:6379,redis-2:6379 \
  -e RATE_LIMITER_STREAM_CONSUMER=rate-limiter-instance-1 \
  --name rate-limiter-service \
  rate-limiter-service:latest
```

### 4) Production Redis recommendations

- Redis Cluster with **AOF persistence** enabled (durability for stream events).
- **Multi-AZ replica placement** for failover across availability zones.
- `maxmemory-policy` set to `noeviction` with sufficient `maxmemory` to prevent evicting hot rate-limit keys.

### 5) Production verification checklist

- `GET /actuator/health` returns `UP` on all nodes.
- `POST /request` returns `200`/`429` as expected.
- `GET /stats` and `GET /users/{id}/usage` reflect traffic.
- Prometheus scrapes `GET /actuator/prometheus`.
- Circuit breaker state visible: `resilience4j_circuitbreaker_state{name="redisRateLimiter"}`.

### 6) Kubernetes deployment shape (reference)

- **Deployment:** 3+ replicas of this app.
- **Service:** ClusterIP (internal) or LoadBalancer (external).
- **Config:** env vars (`SPRING_DATA_REDIS_CLUSTER_NODES`); each pod needs a unique `RATE_LIMITER_STREAM_CONSUMER` — use `$(POD_NAME)` via the Downward API.
- **Probes:** liveness and readiness on `/actuator/health`.
- **HPA:** scale on CPU + request latency percentiles.

## Testing

```bash
# Unit + integration tests (Testcontainers spins up Redis automatically)
./gradlew test

# JMH microbenchmarks (algorithm throughput + routing throughput)
./gradlew jmh
```

### Test coverage

| Test | What it verifies |
|------|-----------------|
| `RateLimiterIntegrationTest` | Full Spring context + Testcontainers Redis; first request returns 200; 429 is returned after burst capacity is exceeded; `/users/{id}/usage` returns data after async analytics |
| `RateLimiterConcurrencyTest` | 100 threads against in-memory port; accepted ≤ limit; accepted + rejected = total |
| `TokenBucketAlgorithmTest` | Rejects exactly at burst capacity |
| `SlidingWindowCounterAlgorithmTest` | Rejects exactly at limit |

### JMH benchmarks

```bash
./gradlew jmh                                          # run all benchmarks
./gradlew jmh -PjmhIncludes=AlgorithmBenchmark -PjmhFork=1 -PjmhWarmupIterations=3 -PjmhIterations=5
./gradlew jmh -PjmhIncludes=RoutingBenchmark -PjmhFork=1 -PjmhWarmupIterations=3 -PjmhIterations=5
```

| Benchmark | Expected result | Interpretation |
|-----------|-----------------|----------------|
| `AlgorithmBenchmark.tokenBucket` | ~18 M ops/s @ 8 threads | Algorithm is never the bottleneck |
| `AlgorithmBenchmark.slidingWindowCounter` | ~17 M ops/s @ 8 threads | Algorithm is never the bottleneck |
| `RoutingBenchmark.routeSingleThread` | ~24 M ops/s | Routing adds ~41 ns — effectively zero overhead |
| `RoutingBenchmark.routeEightThreads` | ~89 M ops/s | Near-linear scaling; no contention on read path |

## What to Improve for Larger Production Scale

- **Redis Cluster with multi-AZ replication** and automated failover for HA.
- **Stream sharding:** multiple stream keys to distribute analytics write load across cluster slots.
- **Consumer group autoscaling:** add/remove consumer instances dynamically based on stream lag.
- **Alerting:** on P99 latency threshold, circuit-breaker open state, and stream consumer lag.
- **Count-Min Sketch** for top-K users at very high user cardinality (>10 M distinct users) to avoid Redis ZSET memory growth.
- **Long-term analytics sink:** export aggregated data to a data warehouse for historical retention beyond the in-memory ring's one-hour window.
