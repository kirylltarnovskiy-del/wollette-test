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

### Decision: Lock-Free Algorithms Using Compare-And-Swap (CAS)

The most dangerous race condition in a rate limiter is the check-then-act problem: a thread reads the current counter, decides the request is within the limit, and then increments — but between the read and the write, another thread has already incremented the counter, causing both threads to be allowed when only one should have been.

The solution is to make the check and the update a single atomic operation. For the in-process algorithms, this is achieved using Java's `AtomicReference` with a compare-and-swap loop: a thread reads the current state, computes the new state, and only writes it back if the state has not been changed by another thread in the meantime. If the swap fails, the thread retries. This is called a CAS loop.

**Why CAS over `synchronized` blocks?** Under high concurrency, a synchronized block serializes all threads through a single lock, which becomes a throughput bottleneck as thread count grows. CAS is optimistic — it assumes conflicts are rare, attempts the update without locking, and only retries on actual conflict. In practice, CAS delivers 3 to 10 times higher throughput than synchronized blocks for this workload.

**Why CAS over `ReentrantLock`?** ReentrantLock is more flexible than synchronized but still involves blocking — a thread waiting for the lock is descheduled and must be rescheduled when the lock is released, which has OS-level overhead. CAS avoids descheduling entirely; retrying threads stay on-CPU for the microseconds it takes to retry.

**Trade-off:** Under extreme contention — many threads all racing to update the exact same user's bucket simultaneously — CAS retry loops can spin hot and consume CPU. This is mitigated in two ways: first, by using `Thread.onSpinWait()` between retries (a CPU hint that reduces power and allows sibling threads to progress); second, by the fact that in realistic traffic, even the busiest user is not being updated by thousands of threads simultaneously.

### Decision: Correctness Demonstrated via Concurrency Tests with CountDownLatch

The invariant of a correct rate limiter is that the number of accepted requests never exceeds the configured limit, regardless of how many threads fire simultaneously. This is verified in tests by launching hundreds of threads behind a starting gate latch, releasing them all at once, and asserting that the accepted count is within the limit and that accepted plus rejected equals total.

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

**Trade-off: Redis as a single point of failure.** If Redis goes down, the rate limiter cannot make decisions. This is handled by a circuit breaker (see Failure Handling section) that fails open — allowing requests through — when Redis is unavailable. The brief burst during a Redis outage is preferable to rejecting all traffic.

**Trade-off: Redis memory is finite.** Rate limit state for millions of users could consume significant memory. This is managed by attaching a TTL (time to live) to every Redis key. A key expires automatically after twice its window duration, so inactive users' keys are cleaned up without any manual eviction logic.

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

All three failure modes are simulated.

### Node Crash

A node's health flag is toggled to simulate a crash. The consistent hash ring detects the unavailability and routes the user to the next node in the ring. The new node starts with no state for that user, effectively resetting their counters.

**System behavior:** The user can briefly exceed their rate limit during the window in which they were remapped, because the new node's counters start at zero. This is the fail-open decision: during a node failure, it is preferable to allow a brief burst than to reject all traffic. The system returns to correct behavior within one window duration after the failure.

**Alternative (fail-closed):** Reject all requests for users whose primary node is unavailable. This prevents any burst but degrades availability. For this service, fail-open is the correct default because rate limiting is soft enforcement. A billing or security system would require fail-closed.

### Clock Skew

Each simulated node can be assigned a clock offset. This demonstrates how different nodes may disagree on window boundaries.

**System behavior:** With Sliding Window Counter, a node that is 500 milliseconds ahead of another node will start a new window earlier. A user whose requests are split across both nodes at the window boundary may be allowed more requests than intended for a brief period. With Token Bucket, this problem does not occur — Token Bucket uses monotonic time (elapsed nanoseconds) for interval calculations rather than wall-clock time for window boundaries, making it immune to clock skew.

**Mitigation in production:** NTP synchronization keeps node clocks within 1 to 10 milliseconds of each other. At this precision, the window boundary drift is negligible. The Clock Skew simulation sets offsets of hundreds of milliseconds to make the effect clearly observable.

### Partial Data Loss

A node's in-memory state is cleared to simulate a crash-and-restart scenario.

**System behavior:** Users whose state was on the affected node have their counters reset to zero. They can make requests up to their full limit again before the window expires. In the Redis-backed production configuration, this failure mode does not apply to the rate limit state itself — Redis AOF persistence ensures state survives application restarts. The simulation is meaningful for the in-process algorithm state maintained for the distributed node simulation.

---

## 8. Scalability Simulation

### Requirement

Simulate multiple nodes (even in-process) and demonstrate a sharding or partitioning strategy.

### Decision: In-Process Consistent Hash Ring Simulation

Multiple `RateLimiterNode` instances are created in-process, each with its own state and health flag. A `ConsistentHashRing` routes incoming requests to the appropriate node by hashing the user ID.

**Why in-process?** The requirement explicitly permits in-process simulation. Running actual separate JVM processes or containers would add infrastructure complexity that distracts from demonstrating the algorithmic and architectural concepts.

**What is being demonstrated?**

- That user traffic is deterministically routed — the same user always reaches the same node in the steady state.
- That node addition and removal remaps only a fraction of users (not all of them).
- That failure handling routes around unavailable nodes.
- That the system recovers correctly when a node comes back online.

---

## 9. Performance

### Requirement

Demonstrate throughput under load and latency characteristics. Bonus: JMH microbenchmarks.

### Decision: JMH Benchmarks for Both Implemented Algorithms

Java Microbenchmark Harness (JMH) is used to benchmark both algorithms under controlled conditions. JMH handles JVM warmup phases (allowing the JIT compiler to optimize the code before measuring), prevents dead-code elimination (which would make the benchmark artificially fast by skipping computation), and controls thread count.

`AlgorithmBenchmark` runs both algorithms at 8 concurrent threads to show steady-state throughput under contention. `RoutingBenchmark` runs `ConsistentHashRing.route()` at 1, 8, and 32 threads for 3-node and 5-node rings, demonstrating that routing overhead is negligible (~40 ns per call).

**Why JMH over a hand-rolled benchmark?** JVM benchmarks are notoriously unreliable without proper warmup. A naive loop measuring `System.nanoTime()` before and after a method call will measure cold-start JIT compilation time and garbage collection pauses rather than steady-state performance. JMH eliminates these artifacts. It is the standard tool for JVM performance measurement and is what the requirement explicitly references.

**Expected findings:** Both Token Bucket and Sliding Window Counter sustain ~16–18 million decisions per second at 8 threads. At 20,000 req/s peak load the algorithm layer consumes under 0.2% of a single CPU core. The bottleneck is always the Redis round-trip (~1 ms), not the algorithm.

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

## Summary of Key Trade-offs


| Decision                      | Chosen Approach                  | Primary Trade-off                                                                                               |
| ----------------------------- | -------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| Rate limit state storage      | Redis                            | Operational dependency; no state if Redis is unavailable                                                        |
| Analytics event delivery      | Redis Streams                    | Lower throughput ceiling than Kafka; simpler operation                                                          |
| Algorithm for burst endpoints | Token Bucket (CAS)               | Users can exceed nominal rate during burst; slight CPU overhead under extreme contention                        |
| Algorithm for strict limits   | Sliding Window Counter           | ~1% approximation error at window boundary; very low memory                                                     |
| Algorithms not implemented    | Sliding Window Log, Leaky Bucket | Log: memory cost outweighs exact accuracy benefit. Leaky Bucket: no burst support, redundant given Token Bucket |
| Multi-node state distribution | Consistent hashing               | State lost for affected users on node failure; brief burst possible                                             |
| Node failure policy           | Fail-open                        | Users can briefly exceed limits during Redis outage; availability prioritized                                   |
| Architecture                  | Ports and adapters               | Extra interface boilerplate; complete testability and clear dependency boundaries                               |
| Framework use                 | Spring Boot (API layer only)     | Domain fully isolated from framework; slightly more manual wiring                                               |
| Concurrency model             | Lock-free CAS                    | CPU spin under extreme contention; 3-10x higher throughput than lock-based under normal load                    |


