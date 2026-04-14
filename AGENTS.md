# wollette-test Agent Guide

## Purpose
This repository is a **test task project** that implements a distributed rate limiter and analytics service using Spring Boot, Redis (standalone for dev, cluster for production), and standard layered architecture.

It is not a production system. It exists to demonstrate design thinking, algorithm knowledge, and architectural trade-offs in a realistic but bounded scope.

Use this guide to keep agent behavior consistent with project conventions.

## Project Snapshot
- Language/runtime: Java 21, Spring Boot
- Build tool: Gradle (`./gradlew`, `./gradlew.bat`)
- Core areas:
  - `src/main/java/com/ratelimiter/controller`: REST controllers
  - `src/main/java/com/ratelimiter/service`: business logic services and algorithm strategies
  - `src/main/java/com/ratelimiter/repository`: Redis data access (Lua script execution)
  - `src/main/java/com/ratelimiter/model`: domain objects (enums, records, events)
  - `src/main/java/com/ratelimiter/dto`: request/response DTOs
  - `src/main/java/com/ratelimiter/config`: configuration and properties
  - `src/main/java/com/ratelimiter/event`: Redis stream publisher and consumer
- Benchmarks: `src/jmh/java/com/ratelimiter/benchmark`
- k6 scenarios: `scenarios/`

## Architectural Rules
- Follow standard Spring Boot layered architecture: controller → service → repository.
- Keep model/DTO classes free of framework logic.
- Services contain business logic; repositories handle data access.
- Preserve rate limit decision semantics: all rules for a tier must pass.

## Common Workflows
### Code changes
1. Identify whether change belongs to controller, service, repository, model, config, or event.
2. Prefer small, targeted edits over broad refactors.
3. Keep naming and terminology consistent with `README.md` and `DESIGN_DECISIONS.md`.

### Validation
- Run tests after functional edits:
  - `./gradlew test` (or `.\gradlew.bat test` on Windows)
- Run focused benchmarks only when performance behavior changes:
  - `./gradlew jmh -PjmhIncludes=AlgorithmBenchmark -PjmhFork=1 -PjmhWarmupIterations=3 -PjmhIterations=5`

### Local stack checks
- Multi-node stack: `docker compose up --build -d`
- Health: `GET /actuator/health` on `:8081`, `:8082`, `:8083`

## Testing Expectations
- Add or update unit tests when algorithm or service logic changes.
- Keep integration behavior aligned with `RateLimiterIntegrationTest`.
- For concurrency-sensitive logic, verify accepted requests do not exceed configured limits.

## Documentation Expectations
- Update `README.md` when API, runtime commands, or architecture behavior changes.
- Update `DESIGN_DECISIONS.md` when changing trade-offs or decision rationale.
- Keep examples and scenario references accurate (`SCALABILITY_SCENARIOS.md`, `scenarios/*.js`).
- **Test task framing:** When documenting trade-offs, design decisions, or known limitations, always distinguish between what is acceptable in this test task scope and what would need to change in a real production environment. Explicitly call out gaps such as:
  - Simplifications made for demonstration purposes
  - Missing operational concerns (monitoring, alerting, secrets management, etc.)
  - Scale limitations that would not hold in production traffic
  - Security or resilience shortcuts taken for brevity

## Guardrails
- Do not introduce a second source of truth for rate limit state outside the repository layer.
- Do not bypass Redis/Lua atomicity for distributed decision paths.
- Do not silently change fail-open behavior around Redis outages.
