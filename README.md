# Distributed Rate Limiter + Analytics Service

Spring Boot service that applies per-user API rate limits and exposes near real-time usage analytics.  
This repository is intentionally scoped as a **test task project**: it demonstrates architecture and trade-offs, not full production hardening.

## Start here

- Setup and usage are documented in this README.
- Design rationale, trade-offs, alternatives, and failure analysis are in [`DESIGN_DECISIONS.md`](DESIGN_DECISIONS.md).
- Load/failure simulation runbooks are in [`SCALABILITY_SCENARIOS.md`](SCALABILITY_SCENARIOS.md).

## Tech stack

- Java 21
- Spring Boot
- Redis (standalone for local dev, Redis Cluster for distributed mode)
- Gradle
- Docker Compose (for local distributed stack)

## Architecture at a glance

- Layered application structure: controller -> service -> repository.
- Shared distributed limit state stored in Redis and updated atomically with Lua scripts.
- Rate-limit decision events are published asynchronously and consumed for analytics aggregation.

For detailed algorithm and architecture decisions, see [`DESIGN_DECISIONS.md`](DESIGN_DECISIONS.md).

## Project structure

- `src/main/java/com/ratelimiter/controller` - REST endpoints
- `src/main/java/com/ratelimiter/service` - business logic
- `src/main/java/com/ratelimiter/repository` - Redis/Lua data access
- `src/main/java/com/ratelimiter/model` - domain objects
- `src/main/java/com/ratelimiter/dto` - request/response DTOs
- `src/main/java/com/ratelimiter/config` - runtime/configuration wiring
- `src/main/java/com/ratelimiter/event` - analytics event publish/consume
- `src/jmh/java/com/ratelimiter/benchmark` - benchmarks
- `scenarios/` - k6 scenarios

## Run locally

### Prerequisites

- Java 21
- Docker + Docker Compose

### Option A: full distributed stack (recommended)

```bash
docker compose up --build -d
```

This starts:
- 3 Spring Boot app instances (`8081`, `8082`, `8083`)
- 6 Redis nodes (3 masters + 3 replicas) and cluster bootstrap container

Verify:

```bash
curl -s http://localhost:8081/actuator/health
curl -s http://localhost:8082/actuator/health
curl -s http://localhost:8083/actuator/health
```

### Option B: single app instance for development

```bash
docker run -d --name redis-dev -p 6379:6379 redis:7.2-alpine
./gradlew bootRun
```

## Basic API usage

### Check request allowance

```bash
curl -X POST localhost:8080/request \
  -H "Content-Type: application/json" \
  -d '{"userId":"free-user","endpoint":"/payments"}'
```

### Trigger throttling quickly

```bash
for i in $(seq 1 20); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/request \
    -H "Content-Type: application/json" \
    -d '{"userId":"free-user","endpoint":"/payments"}'
done
```

### Read analytics and health

```bash
curl "localhost:8080/stats?windowSeconds=60"
curl localhost:8080/users/free-user/usage
curl localhost:8080/actuator/health
curl localhost:8080/actuator/prometheus
```

## Configuration

### Key environment variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SPRING_DATA_REDIS_CLUSTER_NODES` | Yes (cluster mode) | - | Comma-separated Redis cluster node list (`host:port`) |
| `REDIS_HOST` | Yes (standalone mode) | `localhost` | Standalone Redis host |
| `REDIS_PORT` | Yes (standalone mode) | `6379` | Standalone Redis port |
| `RATE_LIMITER_STREAM_CONSUMER` | Recommended | hostname | Unique consumer id per app instance |
| `RATE_LIMITER_STREAM_GROUP` | No | `analytics` | Redis Streams consumer group |
| `RATE_LIMITER_STREAM_BATCH_SIZE` | No | `100` | Stream poll batch size |

For detailed behavior and runtime update strategy, see [`DESIGN_DECISIONS.md`](DESIGN_DECISIONS.md).

## Testing

```bash
./gradlew test
```

Optional benchmarks:

```bash
./gradlew jmh
```

For scenario-based validation (outages, failover, throughput), use [`SCALABILITY_SCENARIOS.md`](SCALABILITY_SCENARIOS.md).

## Production notes

This project includes production-oriented patterns, but remains test-task scoped.  
Before real production use, review limitations and recommended changes in [`DESIGN_DECISIONS.md`](DESIGN_DECISIONS.md), especially around:

- analytics hot keys and sharding strategy
- observability and operational hardening
- resilience behavior during Redis failures
- long-term analytics storage strategy
