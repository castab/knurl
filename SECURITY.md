# Security Policy

## Reporting a vulnerability

Please **do not open a public issue** for a security vulnerability - that discloses it to everyone,
including anyone who might exploit it, before a fix exists.

Instead, use GitHub's private vulnerability reporting:

1. Go to the [Security tab](../../security) of this repository.
2. Click **Report a vulnerability**.
3. Describe the issue, how to reproduce it, and its impact.

This opens a private draft advisory visible only to the repository maintainer and you, with its own
discussion thread, until a fix is ready and the advisory is published.

## What's in scope

Both services (`ingestion-service`, `presentation-service`), `shared-domain`, the database schema
and migrations, and the CI/CD workflows in `.github/`. Vulnerabilities in third-party dependencies
are also welcome here if you've confirmed they're actually reachable through this codebase - a CVE
in an unused code path of a transitive dependency is lower priority than one this project actually
exercises.

## What to expect

This is a small, actively-developed project maintained by one person, not a company with an SLA.
There's no guaranteed response time, but security reports get priority over ordinary feature work.

## What this project already does

For context on the baseline you're reporting against:

- **Secrets at rest**: the per-account admin/read tokens are hashed (SHA-256, one-way); the
  Instagram access token is encrypted (AES-256-GCM) under a key kept outside the database. See
  `shared-domain/src/main/kotlin/knurl/domain/security/`.
- **CI**: every push/PR runs the full test suite, a secret scan of the full git history
  (`.github/workflows/security.yml`), and submits the resolved Gradle dependency graph so GitHub's
  Dependabot alerts can flag known-vulnerable dependencies.
- **Tenant isolation**: one deployment serves many Instagram accounts; every admin and gallery-read
  route is scoped per-account, enforced by `authorizeAccount` in `presentation-service`.

None of that means this project is free of vulnerabilities - it means a report that gets past those
layers is worth taking seriously.
