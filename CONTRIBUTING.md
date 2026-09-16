# Contributing

## Before you start

For anything beyond a small fix, open an issue first to discuss the change - it's a lot cheaper to
align on approach before writing code than after.

## Setup

See [README.md](README.md)'s "Requirements" and "Local development with Docker Compose" sections
for getting a working local environment (JDK 25, Postgres 18+, the bundled Gradle wrapper, and the
`docker-compose.yml` Postgres/MinIO stack).

## Making a change

1. Branch off `main`.
2. Make your change. [AGENTS.md](AGENTS.md) documents the architectural boundaries between
   `shared-domain`, `ingestion-service`, and `presentation-service` - read it before crossing one
   (e.g. presentation-service must never call the Meta Graph API or touch S3 beyond presigning
   reads; only ingestion-service runs Flyway migrations).
3. Add or update tests. This project has no integration test infrastructure (no test containers) -
   the existing pattern is pure, unit-testable functions for anything with real logic, and
   route-level tests that refuse to reach a real database (see `NeverConnectDataSource` in the
   `presentation-service` route tests) for HTTP-layer behavior.
4. **If you changed a route**, regenerate the OpenAPI spec and commit the result:
   ```bash
   ./gradlew :presentation-service:generateOpenApiSpec
   ```
5. **If you changed the database schema**, check whether this project is still in the
   no-production-data phase README.md's "Local development with Docker Compose" section describes -
   if so, fold the change into `V1__init_instagram_aggregator.sql` rather than adding a new
   migration file, and note in your PR that `docker compose down -v` (or an equivalent reset on any
   deployed database) is needed.
6. Run the full check locally before opening a PR:
   ```bash
   ./gradlew build
   ```
   This runs compilation, [Kotlinter](https://github.com/jeremymailen/kotlinter-gradle) (ktlint)
   formatting checks, and the full test suite across all three modules. `./gradlew formatKotlin`
   auto-fixes most formatting issues if the lint step fails.

## Opening a PR

- Target `main`.
- CI (`.github/workflows/ci.yml` and `security.yml`) runs automatically on every PR: build/lint/test,
  a secret scan of the full history, and a dependency-graph submission. A PR that fails any of these
  won't be merged until it's green.
- Describe *why*, not just *what* - this codebase's existing comments favor explaining the reasoning
  behind a decision over restating what the code already says; PR descriptions should do the same.

## Code style

Formatting is enforced by Kotlinter/ktlint, not by convention - if `./gradlew build` passes, the
style is correct. Beyond formatting, match the density and tone of comments already in the file
you're editing: this codebase explains *why*, especially for non-obvious tradeoffs, gotchas, and
architectural boundaries, more than it narrates *what* the code does.
