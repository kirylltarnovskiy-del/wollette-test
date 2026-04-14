# Rate Limiter + Analytics Service — Design Decisions & Trade-offs

This document covers every requirement from the task specification and explains the chosen approach, the alternatives that were considered, and the trade-offs involved in each decision. It is intended as a reference for technical review.

---

## 1. Rate Limiting

### Requirement

Implement `allowRequest(userId, endpoint) → boolean` with support for multiple rate limit rules per user, different limits per user tier (free, premium, enterprise), and burst handling.

### Decision: Multiple Rules Evaluated Sequentially, All Must Pass

Each user tier has a set of rules — for example, a free-tier user has both a per-second rule and a per-minute rule. When `allowRequest` is called, every rule for that user's tier is checked in order. If any single rule rejects the request, `false` is returned immediately. All rules must pass for the request to be allowed.

This means rate limiting is additive and conservative. A free-tier user who has consumed their per-minute budget cannot get a temporary reprieve just because their per-second window has reset.

**Why this approach?** It is the standard industry model. Per-second rules protect downstream services from instantaneous spikes. Per-minute rules enforce fair long-term usage. Both are needed and neither should override the other.

**Alternative considered:** Evaluating rules in parallel and taking the most restrictive result. This produces the same outcome but is harder to reason about and adds synchronization complexity without any practical benefit, since the sequential short-circuit already returns on the first failure.

### Decision: Burst Handling via Token Bucket Burst Capacity

For endpoints where bursts are expected and acceptable, the Token Bucket algorithm is assigned. Its `burstCapacity` parameter is set higher than the sustained rate — for example, a user allowed 10 requests per second can be given a burst capacity of 15. Those extra tokens accumulate during quiet periods and allow short spikes without rejection.

**Trade-off:** Burst capacity means a user can momentarily exceed the nominal rate. This is intentional for consumer-facing APIs where strict per-second enforcement would feel punishing. For billing-critical or security-critical endpoints, burst capacity should be set equal to the sustained rate, effectively disabling bursting.

---

## 2. Concurrency and Correctness

### Requirement

The system must be thread-safe, free of race conditions under high load, and demonstrate correctness under concurrent requests.

### Decision: Atomicity via Redis Lua Scripts

The most dangerous race condition in a rate limiter is the check-then-act problem: a thread reads the current counter, decides the request is within the limit, and then increments — but between the read and the write, another thread has already incremented the counter, causing both threads to be allowed when only one should have been.

The implementation solves this by executing the entire check-and-update as a **single Redis Lua script**. Redis is single-threaded with respect to command execution: a Lua script runs to completion on the server before any other command is processed. The scripts (`token_bucket.lua`, `sliding_window.lua`) read state, compute the new state, write it back, and return the decision — all in one server-side operation. No other client can interleave between the read and the write. This is the distributed equivalent of an atomic compare-and-swap.

### Known Problems with the Current Lua Script Implementation

**1. Redis is a required single point of atomicity.** The entire concurrency guarantee depends on Redis being available. If the circuit breaker opens (see Failure Handling), the fallback is fail-open — requests are allowed without any counter check. During a Redis outage, the check-then-act problem re-emerges fully: there is no in-process fallback that maintains the limit. This is an explicit availability-over-correctness trade-off.

**2. Clock skew on the client side.** The Lua scripts receive `nowMs` as a parameter passed in from the Java application (`Instant.now().toEpochMilli()`). If application nodes have drifted clocks, the `elapsed` calculation inside the Token Bucket script becomes unreliable: a node with a clock running ahead will over-refill tokens; a node with a clock running behind will under-refill. The scripts themselves have no access to a monotonic server-side clock.

**3. Script loading latency on first call.** `DefaultRedisScript` with `ClassPathResource` loads and SHA-caches the script on first execution. Under a cold start with very high initial traffic, this adds a one-time per-node latency spike. Scripts can be pre-loaded with `SCRIPT LOAD` at deploy time to avoid this.

**4. No in-process rate limiting layer.** Every single request requires a network round-trip to Redis for the rate limit decision. At sub-millisecond Redis latency this is acceptable, but under Redis cluster degradation (e.g., leader election, replica promotion) P99 latency spikes directly translate into rate limiter latency spikes. There is no local fast-path.

### Why CAS with a Different Storage Model Could Be Better in Production

A Java CAS loop operating on in-process state eliminates the network entirely — the check-and-update completes in nanoseconds on the same CPU. The trade-off is that this state is local to one JVM and cannot be shared across app nodes. However, there are production architectures that make this work:

- **Sticky routing at the load balancer:** If requests for a given user are always routed to the same app node (by hashing `userId` at the load balancer), then per-node CAS is globally correct — each user's counter lives on exactly one node. This is the architecture that consistent hashing is designed to enable. Counter state can be periodically flushed to Redis for durability without making Redis the synchronization point for every request.
- **Local CAS as a first-pass pre-filter:** Even without sticky routing, a node-local CAS counter can act as a fast rejection layer. If a user is clearly over the limit on the local node, the request is rejected immediately without touching Redis. Only requests that pass the local check proceed to Redis for the authoritative global check. This cuts Redis load proportionally to the rejection rate.
- **Token Bucket with eventual consistency:** Each node holds a fraction of the token budget (total capacity divided by node count). Tokens are consumed locally via CAS with no coordination. Periodically, nodes gossip their consumed counts to reconcile the global budget. This allows brief over-admission at rebalance boundaries but eliminates per-request Redis dependency entirely.

The current implementation favours **operational simplicity and strong consistency** (one Redis cluster, one Lua script, zero per-request coordination between app nodes) over **maximum throughput and Redis independence**. CAS-based local state would be the right next step if Redis latency or availability became the measured production bottleneck.

### Why Couchbase Would Be a Better Fit for CAS-Based Rate Limiting

Couchbase is the most direct upgrade path for this architecture because it makes CAS a first-class storage primitive rather than something emulated on top of a scripting layer.

**Concrete advantages over the Redis Lua approach:**

- **No scripting layer required.** The atomicity guarantee is built into the Couchbase storage engine, not emulated via server-side script execution. There is no equivalent of the script loading latency problem (problem 3 above), and the check-and-update logic lives in application code rather than embedded Lua.
- **Clock skew immunity.** Because the decision logic runs in the application and the CAS value guards the document against stale writes, the `nowMs` clock dependency moves entirely client-side where it is already under the application's control. There is no server-side `elapsed` calculation that a drifted clock can corrupt — the token count and timestamp are just fields in a JSON document, updated atomically only if no one else touched the document first.
- **Sub-millisecond KV latency at scale.** Couchbase key-value operations run directly against memory on the owning node in the cluster; the document is not replicated to other nodes before the response is returned. In practice this is comparable to Redis latency.
- **Built-in durability levels.** Unlike Redis, where persistence is asynchronous and a crash can lose recent writes, Couchbase lets you specify per-operation durability: majority-replicated, persisted to disk, or fire-and-forget. A rate limiter can use fire-and-forget (same consistency as Redis) or step up to majority-replicated for critical enforcement scenarios without changing the client API.

**Trade-off:** Under high contention on a single bucket document — many app nodes retrying CAS simultaneously for the same hot user — the retry loop can amplify write load on that document's owning node. This is the distributed version of the CAS spin problem described above. Mitigation is the same as in-process: use exponential backoff between retries, and accept that hot users under extreme burst will see slightly higher decision latency during the retry window.

**Trade-off: Operational complexity.** Couchbase requires managing a proper cluster with its own rebalancing, index, and eviction mechanics. Redis's operational model is simpler for teams already running it. The CAS gain is worth the switch only if rate limiter correctness during partial outages is a hard requirement — if fail-open is acceptable, Redis Lua remains the simpler path.

---

## 3. Storage Strategy

### Requirement

Choose between in-memory and persistent storage, between single-node and distributed, and justify the decision including trade-offs around latency, consistency, and cost.

### Decision: Redis as the Sole Storage Backend for Both Local and Production

Redis is used for all storage needs: rate limit state, analytics counters, top-user rankings, and the analytics event queue. The same Redis adapter runs in both local development (pointed at a Docker Compose container) and production (pointed at a Redis Cluster). There are no separate in-memory adapters or mock implementations.

**Why Redis over a relational database?**
A relational database operates at 1 to 10 millisecond latency per query and requires connection pool management. Redis over a local network operates at under 1 millisecond. For a rate limiter that is called on every incoming API request, adding even 2 milliseconds of latency per check would be unacceptable. Redis is purpose-built for this use case.

**Why Redis over pure in-memory Java maps?**
Pure in-memory state does not survive application restarts and cannot be shared across multiple instances of the application. If the application scales horizontally, each instance would have its own counter, and a user could make N times the allowed requests by having their traffic distributed across N instances. Redis provides a single shared truth for all instances.

**Why not Cassandra or another distributed database?**
Cassandra offers excellent horizontal write scalability, but its conditional updates (`IF` clauses using Lightweight Transactions) are significantly slower than Redis operations and involve multi-round-trip Paxos consensus. The atomicity guarantees needed for rate limiting — check and increment as one operation — are simpler and faster to express in Redis via Lua scripts.

**Trade-off: Redis as a single point of failure.** If Redis goes down, the rate limiter cannot make decisions. In this project, the circuit breaker is configured to fail open (allow requests) because this limiter is treated as a user-facing API quota control where availability is prioritized over strict enforcement. BUT!!!!! That policy is context-dependent: for outbound protection (protecting downstream dependencies from overload), fail-open is usually the wrong choice because it removes the safety barrier exactly when control is needed most. In that outbound case, the safer policy is fail-closed (block or aggressively shed traffic), optionally with a small local fallback budget, so downstream systems are protected during Redis outages.

**Trade-off: Redis memory is finite.** Rate limit state for millions of users could consume significant memory. This is managed by attaching a TTL (time to live) to every Redis key. A key expires automatically after twice its window duration, so inactive users' keys are cleaned up without any manual eviction logic.

### Redis Sharding Problem: Hot Keys and the Analytics Stream

The current architecture has a structural hot-key problem at two points:

**Rate limit state keys.** Redis Cluster shards keys across masters by hash slot. Because the Token Bucket script uses hash tags (`{userId}`) to pin a user's key to a single slot, all traffic for the same user lands on one master node. An enterprise user generating 10,000 requests per second creates a hot slot on one master that the cluster cannot rebalance away — the hash tag prevents the key from migrating.

**The analytics stream key.** `rate-limit-events` is a single Redis Stream key. A single key maps to exactly one hash slot and therefore one master node. All three app nodes append to it, all three consumer groups read from it, and there is no way to distribute this load across the cluster. This is noted in the Bottlenecks section: mitigation is to shard the stream by `userId % N`, but this requires `N` separate XREADGROUP consumers per app node and `N` streams to monitor operationally.

### Why ClickHouse Would Solve the Analytics Sharding Problem

ClickHouse is a columnar OLAP database built specifically for high-throughput append-only workloads — exactly what the analytics stream produces. Replacing the Redis Stream + `AnalyticsAggregator` pipeline with ClickHouse resolves every sharding limitation described above.

**How the architecture would change:**

Instead of writing rate limit events to a Redis Stream (`XADD rate-limit-events`), each app node would insert a row into a ClickHouse table directly (or buffer via a local queue and batch-insert):

```sql
INSERT INTO rate_limit_events (user_id, endpoint, allowed, ts)
VALUES ('user-123', '/request', 1, now64())
```

ClickHouse `ReplicatedMergeTree` (or `ReplicatedSummingMergeTree` for pre-aggregation) distributes data across shards by the partition key. Setting the partition key to `toYYYYMMDD(ts)` and the shard key to `cityHash64(user_id)` means traffic for every user is spread across all ClickHouse nodes without hash-tag pinning constraints.

**Why this solves the hot-key problem specifically:**

Redis sharding unit is a key. One key means one slot and one master. ClickHouse sharding unit is a row. Rows from the same user are written to the shard selected by the hash of `user_id`, and ClickHouse can rebalance shards independently of which user generated the data. There is no equivalent of the hash-tag constraint. At one million events per second across 10 users, every shard receives writes — not just the one that owns the hot user's slot.

**Analytics query performance:**

The `/stats` endpoint currently aggregates accepted/rejected totals from an in-memory ring buffer populated by stream consumers. Each app node reports only its share. In a ClickHouse model, all nodes write to the same cluster and queries aggregate globally:

```sql
SELECT
    toStartOfSecond(ts) AS second,
    countIf(allowed = 1) AS accepted,
    countIf(allowed = 0) AS rejected
FROM rate_limit_events
WHERE ts >= now() - INTERVAL 60 SECOND
GROUP BY second
ORDER BY second
```

This query runs in milliseconds on very large datasets and returns a globally consistent view regardless of which app node a client queries — something the current per-instance ring buffer cannot provide.

**Trade-off: ClickHouse is not a rate limit state store.** ClickHouse is append-optimized and does not support the read-modify-write pattern that a Token Bucket requires. The rate limit decision path (the Lua scripts, or Couchbase CAS as described above) must stay on a low-latency key-value store. ClickHouse replaces only the analytics pipeline — the Redis Stream, the `AnalyticsAggregator` ring buffer, and the top-user ZSET — not the enforcement layer.

**Trade-off: Operational cost.** ClickHouse adds a second storage system to operate alongside Redis (or Couchbase). This is justified when analytics query volume or cardinality grows large enough that Redis memory and the single-stream bottleneck become measured problems. At lower scale, the current Redis Stream approach is simpler to run.

---

## 4. Rate Limiting Algorithms

### Requirement

Implement and compare at least two algorithms from: Token Bucket, Leaky Bucket, Sliding Window Log, Sliding Window Counter. Explain trade-offs between accuracy, memory, and performance.

Two algorithms are implemented: Token Bucket and Sliding Window Counter. These were selected because they cover opposite ends of the design space — burst-tolerant versus strict-fairness — and together handle every practical rate limiting scenario without unnecessary implementation overhead. The system assigns an algorithm per rule in configuration, so a user's per-second rule can use Token Bucket while their per-minute rule uses Sliding Window Counter.

### Why Not Sliding Window Log or Leaky Bucket?

**Sliding Window Log** stores a timestamped entry for every individual request. This gives exact accuracy but at an unacceptable memory cost for production use. An enterprise user at 1,000 requests per second with a 60-second window requires 60,000 log entries per user in memory, growing linearly with request rate. Under high load it also generates significant garbage collection pressure. The ~1% approximation of Sliding Window Counter is an entirely acceptable trade-off for the order-of-magnitude reduction in memory and elimination of GC overhead.

**Leaky Bucket** enforces a perfectly smooth output rate by draining a queue at a fixed rate. It provides no burst support — a user who is idle for ten seconds cannot save up any capacity for a brief spike. For a user-facing API rate limiter, this is unnecessarily restrictive. Token Bucket solves burst handling naturally through its capacity parameter, making Leaky Bucket redundant in this context. Leaky Bucket is more appropriate for protecting downstream services that require a smooth, metered input rate — a different problem from enforcing user-facing API quotas.

### Token Bucket

A user's bucket holds tokens up to a maximum capacity. Tokens are replenished at a fixed rate. Each request consumes one token. If the bucket is empty, the request is rejected.

This algorithm does not require a background thread. Token replenishment is calculated lazily at request time: when a request arrives, the algorithm computes how many tokens should have been added since the last access and credits them before deciding.

- **Accuracy:** High. Exact token accounting.
- **Memory:** Low. Two values per user (token count, last refill timestamp).
- **Performance:** Very high. Lock-free CAS; benchmarks at tens of millions of operations per second per thread.
- **Burst support:** Native. The bucket capacity directly controls the burst ceiling.
- **Best for:** Endpoints where short bursts are expected and acceptable.

### Sliding Window Counter

Two counters are maintained per user per rule: one for the current time window and one for the previous window. On each request, an interpolated count is calculated by weighting the previous window's count by how much of the current window has elapsed.

For example, if the current window is 60 percent through its duration, the effective count is: (previous count × 0.4) + current count. If this is below the limit, the request is allowed and the current counter is incremented.

- **Accuracy:** ~99%. The interpolation introduces at most about 1% error at window boundaries.
- **Memory:** Very low. Two counters and one timestamp per user per rule.
- **Performance:** Very high. Atomic increment on a counter; close to Token Bucket performance.
- **Burst support:** None by itself, but pairs naturally with Token Bucket for burst rules.
- **Best for:** Strict fairness enforcement where near-exact limits matter and memory is a concern.

### Algorithm Comparison


| Algorithm              | Accuracy | Memory per User | Throughput | Burst Support                | Primary Use Case                  |
| ---------------------- | -------- | --------------- | ---------- | ---------------------------- | --------------------------------- |
| Token Bucket           | High     | Minimal         | Very High  | Yes                          | Default; burst-tolerant endpoints |
| Sliding Window Counter | ~99%     | Minimal         | Very High  | No (pairs with Token Bucket) | Strict fairness enforcement       |


---

## 5. Real-Time Analytics

### Requirement

Expose requests per second, top users, rejected versus accepted counts, and sliding window statistics for the last N seconds.

### Decision: Asynchronous Recording via Redis Streams

When `allowRequest` returns, the rate limit decision is published as an event to a Redis Stream. The method returns the boolean immediately without waiting for analytics to be recorded. A background consumer thread reads from the stream and updates the aggregated metrics in Redis.

**Why not record analytics synchronously inside `allowRequest`?** Synchronous recording would add the cost of Redis writes to the hottest code path. The rate limit decision itself already requires one Lua script execution against Redis; adding more writes would approximately double the latency of every call.

**Why Redis Streams rather than a simple in-memory queue?** An in-memory queue (such as a `BlockingQueue`) loses all events if the application restarts before they are processed. Redis Streams persist events to disk (when AOF persistence is enabled) and maintain a Pending Entry List of consumed-but-not-acknowledged messages. If the consumer crashes after reading an event but before processing it, the event remains in the pending list and is reclaimed and redelivered after a configurable timeout. This gives at-least-once delivery semantics without a separate message broker.

**Why Redis Streams rather than Kafka?** Kafka is the industry standard for high-volume event streaming but introduces substantial operational overhead: broker nodes, ZooKeeper or KRaft coordination, topic partition management, and consumer group offset tracking via a separate system. For this service, Redis is already present as the rate limit store. Redis Streams provide sufficient durability and retry semantics without adding a second infrastructure component to operate.

**Trade-off:** Redis Streams do not match Kafka's throughput ceiling (millions of events per second). For this use case — analytics events generated at the same rate as API requests — the throughput of Redis Streams is more than adequate.

### Decision: Ring Buffer for Per-Second Aggregation

Analytics counts are stored in a circular array of 3,600 slots, one slot per second, covering the last hour. The slot index for any second is that second's Unix timestamp modulo 3,600. When a slot is written to for the first time in a given second, its counters are reset (discarding data from one hour ago). This gives O(1) writes and O(window size) reads for any query of the last N seconds.

### Decision: Redis Sorted Set for Top-K Users

User request counts are stored in a Redis Sorted Set. Each event increments the user's score by one. Fetching the top 10 users is a single Redis command returning the highest-scoring members. Because this lives in Redis, the top-user rankings persist across application restarts and are consistent across multiple application instances.

**Trade-off:** For extremely high cardinality (hundreds of millions of distinct users), a Redis Sorted Set storing every user would consume significant memory. The production-scale solution would be a Count-Min Sketch combined with a bounded min-heap, which approximates top-K with sub-linear memory. For this service's expected scale, the exact Sorted Set approach is appropriate.

### Why This Design Is Appropriate for a Test Task — and What Production Would Change

This analytics implementation makes a conscious trade: keep everything inside Redis so the system has zero extra infrastructure. The ring buffer lives in JVM heap (3,600 `AtomicLong` pairs), the per-user usage counters live in Redis hashes, and the top-user ranking lives in a Redis Sorted Set. For a test task, this is the right call — the important concepts (async delivery, at-least-once semantics, O(1) aggregation, global top-K) are all demonstrated without spinning up additional systems.

Two production-grade alternatives are worth understanding in detail: **Cassandra** for durable per-user time-series storage, and **ClickHouse** for high-throughput OLAP analytics.

### Alternative: Cassandra for Per-User Time-Series

Cassandra is a wide-column store designed around write-heavy, time-series workloads with predictable read patterns. It is a natural fit for per-user usage history where the query pattern is always "give me user X's events in time range [T1, T2]".

**How the schema would look:**

```cql
CREATE TABLE rate_limit_events (
    user_id   text,
    ts        timestamp,
    endpoint  text,
    allowed   boolean,
    tier      text,
    PRIMARY KEY (user_id, ts)
) WITH CLUSTERING ORDER BY (ts DESC)
  AND default_time_to_live = 2592000;  -- 30 days
```

The partition key is `user_id`, so all events for a given user land on the same set of replicas. The clustering key is `ts`, so events are physically stored in descending time order and range queries like "last 60 seconds for user X" are a single sequential read with no scatter-gather. TTL is built in — no separate eviction logic like the `PEXPIRE` workaround in the current Redis adapter.

**What it replaces in this app:**

- The `analytics:user:{id}` Redis hashes (per-user accepted/rejected totals) — replaced by aggregating from the Cassandra time-series.
- The ring buffer in `AnalyticsAggregator` — replaced by a `GROUP BY toSecond(ts)` query scoped to the last N seconds.

**Why it was not chosen for this task:** Cassandra requires at minimum a 3-node cluster for meaningful replication, a separate coordinator process, and careful tuning of consistency levels for counter workloads. The operational cost is not justified when the goal is to demonstrate the analytics concepts.

**Remaining limitation compared to ClickHouse:** Cassandra is excellent for single-user lookups but slow for cross-user aggregations like "total requests per second across all users" — that query requires reading from every partition. ClickHouse handles that case better.

### Alternative: ClickHouse for OLAP Analytics (Production Recommendation)

ClickHouse is a columnar OLAP database built for exactly this workload: append-only, high-throughput event streams with arbitrary aggregation queries. As discussed in Section 3, it also resolves the Redis hot-key problem for the analytics stream. This is the recommended path if this service were taken to production.

**How the schema would look:**

```sql
CREATE TABLE rate_limit_events
(
    user_id   String,
    endpoint  String,
    allowed   UInt8,
    tier      String,
    ts        DateTime64(3)
)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/rate_limit_events', '{replica}')
PARTITION BY toYYYYMMDD(ts)
ORDER BY (user_id, ts)
TTL ts + INTERVAL 30 DAY;
```

**What each analytics query becomes:**

The `/stats?window=60` endpoint, currently served by the in-process ring buffer (which only reflects the current node's consumed share of the stream), becomes a globally consistent query:

```sql
SELECT
    toStartOfSecond(ts) AS second,
    countIf(allowed = 1) AS accepted,
    countIf(allowed = 0) AS rejected
FROM rate_limit_events
WHERE ts >= now() - INTERVAL 60 SECOND
GROUP BY second
ORDER BY second
```

The `/users/{id}/usage` endpoint, currently served by Redis hashes written by `AnalyticsAggregator`, becomes:

```sql
SELECT
    countIf(allowed = 1) AS accepted,
    countIf(allowed = 0) AS rejected,
    count()               AS total
FROM rate_limit_events
WHERE user_id = 'user-123'
  AND ts >= now() - INTERVAL 30 DAY
```

The top-users ranking, currently a Redis Sorted Set with unbounded memory growth, becomes:

```sql
SELECT user_id, count() AS requests
FROM rate_limit_events
WHERE ts >= now() - INTERVAL 1 DAY
GROUP BY user_id
ORDER BY requests DESC
LIMIT 10
```

**Key differences from the current implementation:**


| Concern                 | Current (Redis)                                 | ClickHouse                                     |
| ----------------------- | ----------------------------------------------- | ---------------------------------------------- |
| Per-second stats source | In-process ring buffer — per-node partial view  | Global query across all nodes' events          |
| Per-user usage          | Redis hash with `HINCRBY` — lost on hash expiry | Time-series rows — TTL handled by table engine |
| Top-K users             | Redis Sorted Set — unbounded memory             | Aggregation query — no memory growth           |
| Retention policy        | Manual `PEXPIRE` on each key                    | Declarative `TTL ts + INTERVAL 30 DAY`         |
| Cardinality limit       | Memory-bound (millions of keys expensive)       | Column-compressed — billions of rows routine   |


**Trade-off: ClickHouse is not a key-value store.** It cannot replace Redis for the rate limit decision path (Token Bucket CAS / Lua scripts require sub-millisecond read-modify-write, which ClickHouse does not support). ClickHouse replaces only the analytics pipeline. In a production architecture, both would coexist: Redis (or Couchbase) for the enforcement hot path, ClickHouse for analytics.

**Trade-off: Insertion latency.** ClickHouse is optimized for batch inserts, not single-row inserts. The current architecture fires one `XADD` per event to the Redis Stream. In a ClickHouse setup, the equivalent would be batching events in a local buffer (or continuing to use Redis Streams as a transport) and flushing to ClickHouse in bulk every few seconds. This adds a small analytics delay but is standard practice and does not affect the rate limiting decision latency at all.

---

## 6. API Layer

### Requirement

Provide `POST /request`, `GET /stats`, and `GET /users/{id}/usage`.

### Decision: Standard HTTP Status Codes and Rate Limit Headers

`POST /request` returns HTTP 200 with `{ "allowed": true }` when the request is permitted and HTTP 429 (Too Many Requests) with `{ "allowed": false }` when rejected. The 429 response includes a `Retry-After` header indicating when the client may retry.

These are not arbitrary choices — RFC 6585 defines 429 as the correct status code for rate limiting, and `Retry-After` is the standardized header for communicating backoff time. Clients that respect HTTP standards will automatically back off without custom handling.

---

## 7. Failure Handling

### Requirement

Simulate at least one failure mode (node crash, clock skew, or partial data loss) and explain system behavior.

The failure simulations in this project target Redis availability, which is the real source of distributed coordination risk in the current architecture. Two failure modes are runnable as live k6 scenarios against the full Docker Compose stack.

### Failure Mode 1: Full Redis Outage

**Simulation:** `scenarios/redis-outage.js` drives live HTTP traffic while Redis is killed mid-test (`docker compose stop redis-node-0 redis-node-1 redis-node-2`) and then restarted.

**System behavior — three phases:**


| Phase    | Duration | What happens                                                                                                                                                                |
| -------- | -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Healthy  | 0—30 s   | Normal 200/429 mix. Circuit breaker CLOSED.                                                                                                                                 |
| Outage   | 30—90 s  | All Redis calls fail. Resilience4j opens the circuit. `allowOnFailure()` returns `true` for every request. Rate limiting pauses — 429s stop, everything gets 200. Zero 5xx. |
| Recovery | 90—120 s | Redis restarts. Circuit transitions HALF-OPEN — CLOSED. 429s resume within seconds.                                                                                         |


**Implementation detail:** Both `allowTokenBucket` and `allowSlidingWindowCounter` in `RedisRateLimiterAdapter` are annotated `@CircuitBreaker(name = "redisRateLimiter", fallbackMethod = "allowOnFailure")`. The fallback returns `true` unconditionally. This is an explicit availability-over-correctness trade-off: brief over-counting during an outage is preferable to denying all traffic with 5xx errors.

**Test task scope vs production:** Fail-open is the right default for a soft-enforcement rate limiter. A billing or fraud-control system would require fail-closed — or a local in-memory fallback counter that degrades gracefully — rather than dropping all enforcement.

### Failure Mode 2: Redis Cluster Master Failover

**Simulation:** `scenarios/cluster-failover.js` kills one Redis cluster master (`docker compose stop redis-node-1`) mid-traffic and restarts it later, with three users whose keys are distributed across all three shards.

**System behavior — three phases:**


| Phase         | Duration | What happens                                                                                                                                                                                                                                                                                                                                                 |
| ------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Healthy       | 0—30 s   | Full cluster up. Normal 200/429 mix. `rl_failover_errors=0`.                                                                                                                                                                                                                                                                                                 |
| Election      | 30—90 s  | `redis-node-1` dies. The cluster elects `redis-node-4` (its replica) as the new master in ~1—5 s. During the election window, requests for keys mapped to the lost shard may see brief connection errors — `rl_failover_errors` spikes. The circuit breaker absorbs these as fail-open. After Lettuce adaptive topology refresh, full rate limiting resumes. |
| Reintegration | 90—120 s | `redis-node-1` restarts and rejoins as a replica. Zero client-visible disruption.                                                                                                                                                                                                                                                                            |


**Key assertion:** `rl_server_errors` stays at zero throughout all phases. The 1—5 s election window produces at most ~30 `rl_failover_errors` (transport-level, not 5xx), which the scenario threshold permits.

### What is not simulated in the HTTP path

Clock-skew and in-memory partial-data-loss simulations (`RateLimiterNode.setClockOffsetMs()`, state clearing) exist in the codebase but are exercised by JMH benchmarks rather than the runtime API decision path. For this test task that scope is sufficient; in a production deployment these would be covered by chaos engineering tooling (for example Chaos Mesh or Gremlin).

---

## 8. Scalability Simulation

### Requirement

Simulate multiple nodes (even in-process) and demonstrate a sharding or partitioning strategy.

### Decision: Multi-Instance App + Redis Cluster Partitioning

Three Spring Boot app instances (`app-1`, `app-2`, `app-3`) serve traffic against a 6-node Redis Cluster (3 masters + 3 replicas). App instances are stateless with respect to rate-limit counters — Redis is the single shared source of truth.

**Sharding strategy:** Redis Cluster hash-slot partitioning. Rate-limit keys use Redis hash tags — for example `rl:tb:{<userId>:<endpoint>:<rule>}` and `rl:sw:{<userId>:<endpoint>:<rule>}:<windowStart>` — so all keys for a given user+endpoint+rule land on the same shard and can be operated on atomically by a single Lua script execution.

**What is demonstrated:**

- Global rate-limit enforcement across all three app instances, proven by `steady-state.js` showing identical accepted/rejected totals on every node.
- Shard-level failure tolerance, demonstrated by `cluster-failover.js`.
- Stateless app tier: any app node can handle any user without coordination between app instances.

### Production Kubernetes Architecture

The Docker Compose topology maps directly to a Kubernetes deployment. The following describes the target production architecture and what would change from this test task setup.

**App tier — Deployment + HorizontalPodAutoscaler**

The three Spring Boot containers become a `Deployment`. Because all rate-limit state lives in Redis and no state is held in-process, every pod is fully interchangeable — scale-out adds capacity without resharding or warm-up, and scale-in loses nothing. n* and m* values should be selected based on expected traffic. 

```yaml
# Scales between n and m replicas based on CPU utilization
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: rate-limiter-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: rate-limiter
  minReplicas: n
  maxReplicas: m
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 60
```

An `Ingress` (nginx or a cloud-native controller) handles TLS termination and routes traffic to a `Service` of type `ClusterIP`. Because the app tier is stateless, sticky sessions and consistent hashing at the load balancer are not required — any pod can serve any user correctly.

**Redis tier — StatefulSet via operator or Helm**

Redis Cluster runs as a `StatefulSet`, typically provisioned by the [Redis Operator](https://github.com/OT-CONTAINER-KIT/redis-operator) or the Bitnami Helm chart. Replicas provide read redundancy and automatic failover; the operator handles slot rebalancing when masters are added or removed.

Lettuce (the Redis client used by Spring Data Redis) maintains a live topology view using adaptive refresh. When a master is replaced by its replica — or when new masters are added during a reshard — Lettuce detects the change on MOVED/ASK errors and reconnects without requiring app restarts. This is the same mechanism exercised by `cluster-failover.js`.

**Comparison: test task vs production**


| Concern                  | Test task (Docker Compose)    | Production (Kubernetes)                    |
| ------------------------ | ----------------------------- | ------------------------------------------ |
| App scaling              | Fixed 3 instances             | HPA, n — m replicas                        |
| Redis provisioning       | Manual init container         | Operator or Helm, automated                |
| Redis persistence        | None (ephemeral)              | RDB + AOF on PersistentVolumeClaims        |
| TLS                      | None                          | TLS between pods and Redis                 |
| Authentication           | None                          | `requirepass` / ACLs via Kubernetes Secret |
| Redis horizontal scaling | Fixed 3 masters               | `redis-cli --cluster reshard`              |
| Observability            | `/actuator/prometheus` scrape | Prometheus + Grafana dashboards, alerting  |
| Secrets management       | Plain config                  | Kubernetes Secrets or Vault integration    |


**Test task scope vs production:** The Docker Compose setup demonstrates the same architectural properties as the Kubernetes deployment would — stateless app tier, Redis-backed global state, cluster failover. What is not simulated here includes persistent storage, TLS, secrets management, and autoscaling. All of these would be required before production use.

---

## 9. Performance

### Requirement

Demonstrate throughput under load and latency characteristics. Bonus: JMH microbenchmarks.

Java Microbenchmark Harness (JMH) is used to benchmark both algorithms under controlled conditions. JMH handles JVM warmup phases (allowing the JIT compiler to optimize the code before measuring), prevents dead-code elimination (which would make the benchmark artificially fast by skipping computation), and controls thread count.

`AlgorithmBenchmark` runs both algorithms at 8 concurrent threads to show steady-state throughput under contention. `RoutingBenchmark` runs `ConsistentHashRing.route()` at 1, 8, and 32 threads for 3-node and 5-node rings, demonstrating that routing overhead is negligible (~40 ns per call).

---

## 10. Clean Architecture

### Requirement

Clear separation of concerns, testable design, minimal framework reliance.

### Decision: Ports and Adapters (Hexagonal Architecture) via Package Discipline

The codebase is organized into four packages with a strict dependency rule: outer layers may depend on inner layers, but inner layers may not depend on outer layers.

- **Domain** — algorithms, rules, models. Pure Java. No framework imports. No infrastructure imports.
- **Application** — the `RateLimiterService` containing `allowRequest`. Depends on domain and on port interfaces. No framework imports. No Redis imports.
- **Infrastructure** — Redis adapters implementing the port interfaces. Depends on application and domain. Contains Spring and Redis imports.
- **API** — Spring Boot controllers. Depends on application. Contains Spring MVC imports.

The port interfaces (defined in the application layer) describe what the application needs from the outside world — a way to check and update rate limit state, a way to publish events — without specifying how those needs are met. The infrastructure layer provides the Redis implementations.

**What this enables:** The domain and application layers can be tested with zero infrastructure. An algorithm can be instantiated with `new` and tested with a simple boolean assertion. The `RateLimiterService` can be tested by providing a stub implementation of the port interfaces. Redis-specific behavior is tested separately in integration tests that spin up a real Redis instance.

**Trade-off:** This structure requires defining interface contracts even for things that will only ever have one implementation (such as the Redis state store). This adds a small amount of boilerplate. The benefit — complete testability without infrastructure — justifies this cost.

---

## 11. Observability

### Requirement

Include a logging strategy, metrics (Micrometer or custom), and debuggability.

### Decision: Structured JSON Logging with Per-Request Context via MDC

Every log line is emitted in JSON format using a Logback JSON encoder. Before any rate limit decision is made, the user ID, request ID, and endpoint are placed into the Mapped Diagnostic Context (MDC). All log statements made during that request's processing automatically include these fields.

**Why structured JSON?** Log aggregation systems (such as Elasticsearch, Datadog, and Grafana Loki) index JSON fields natively. A developer investigating "why was user X rate limited at time T" can filter by `userId=X` and instantly retrieve every log line from that request's processing, including which rule rejected it and what the counter value was. Plain text logs require regex parsing and are much harder to query.

### Decision: Micrometer Metrics Exported to Prometheus

Micrometer provides a vendor-neutral metrics API. The same metric definitions work with Prometheus, Datadog, CloudWatch, and others — only the exporter changes. Three categories of metrics are captured:

- **Counters:** Total requests broken down by result (allowed/rejected) and user tier.
- **Timers:** Decision latency with P50, P95, and P99 percentiles. This is the most operationally important metric — a sudden rise in P99 latency indicates Redis slowness or CAS contention.
- **Gauges:** Current count of users with active rate limit state in memory.

**Cardinality discipline:** Metrics are not tagged with raw user IDs or raw endpoint paths. Tagging with user IDs would create one time series per user — with a million users, this would create a million time series and crash Prometheus. Instead, requests are tagged by tier (three possible values) and by sanitized endpoint pattern (dynamic path segments replaced with `{id}`).

### Decision: Circuit Breaker Around Redis with Metrics Integration

Resilience4j wraps the Redis adapter. The circuit breaker's state transitions (closed, open, half-open) are automatically exported as Micrometer metrics. An alert on `resilience4j.circuitbreaker.state == open` indicates Redis is unreachable, which is immediately visible in dashboards without any custom instrumentation.

---

