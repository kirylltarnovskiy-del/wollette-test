# wollette-test Agent Guide

## Purpose
This repository is a **test task project** that implements a distributed rate limiter and analytics service using Spring Boot, Redis (standalone for dev, cluster for production), and hexagonal architecture.

It is not a production system. It exists to demonstrate design thinking, algorithm knowledge, and architectural trade-offs in a realistic but bounded scope.

Use this guide to keep agent behavior consistent with project conventions.

## Project Snapshot
- Language/runtime: Java 21, Spring Boot
- Build tool: Gradle (`./gradlew`, `./gradlew.bat`)
- Core areas:
  - `src/main/java/com/ratelimiter/domain`: domain models and algorithms
  - `src/main/java/com/ratelimiter/application`: application services and ports
  - `src/main/java/com/ratelimiter/infrastructure`: Redis and stream adapters
  - `src/main/java/com/ratelimiter/api`: controllers and DTOs
- Benchmarks: `src/jmh/java/com/ratelimiter/benchmark`
- k6 scenarios: `scenarios/`

## Architectural Rules
- Keep domain and application logic framework-free where possible.
- Respect ports/adapters boundaries:
  - Domain does not depend on Spring or Redis classes.
  - Application depends on domain + port interfaces.
  - Infrastructure implements ports and contains Redis/Spring integration.
- Preserve rate limit decision semantics: all rules for a tier must pass.

## Common Workflows
### Code changes
1. Identify whether change belongs to domain, application, infrastructure, or API.
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
- Do not introduce a second source of truth for rate limit state outside configured ports.
- Do not bypass Redis/Lua atomicity for distributed decision paths.
- Do not silently change fail-open behavior around Redis outages.
