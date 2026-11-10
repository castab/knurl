# Contributing

Discuss substantial changes in an issue or an implementation plan. Explicit user-authorized work does not require opening another issue before proceeding.

Use JDK 25, PostgreSQL 18+, the Gradle wrapper, and the local Garage/PostgreSQL stack described in [README](README.md). `knurl-app` is the sole executable; `shared-domain` remains free of HTTP, serialization, and application-framework dependencies.

Read [AGENTS.md](AGENTS.md) and [architecture](docs/architecture.md) before changing tenancy, credential persistence, ingestion, or storage cleanup. Preserve streaming media processing and bounded concurrency.

Use Kotest FunSpec and MockK for collaborators. Do not introduce hand-rolled fake/recording collaborators. Live-service tests must be gated by the integration flags described in [operations](docs/operations.md#integration-tests) and use isolated schemas/buckets.

After changing routes, run `sh gradlew :knurl-app:generateOpenApiSpec` and commit `knurl-app/openapi.json`. The offline generator uses exactly the production contract wiring. Route bodies use KotlinxSerialization; OpenAPI schema generation uses Jackson.

The consolidation explicitly resets the old migration history. From this baseline onward, add forward-only ordered migrations under `shared-domain/src/main/resources/db/migration`; do not edit an applied migration or require routine operator resets.

Run `sh gradlew build --no-daemon` before review. Use `sh gradlew formatKotlin` to fix formatting. CI runs build/lint/tests, a container build, a secret scan, and dependency submission. Release workflow edits must preserve stable/RC tag separation and publish only `castab/knurl`; publishing a release requires a separate explicit request.
