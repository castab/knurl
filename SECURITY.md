# Security policy

Report vulnerabilities through GitHub's private vulnerability reporting on this repository's Security tab, rather than a public issue. Include reproduction steps, affected versions, and impact. This project has no guaranteed response SLA.

Scope includes `knurl-app`, `shared-domain`, schema, bundled UI, Garage development setup, and CI/CD workflows.

## Credential boundaries

Instagram access tokens, account admin tokens, and S3 secret access keys are encrypted using AES-256-GCM under `CREDENTIAL_ENCRYPTION_KEY`, stored outside PostgreSQL. APIs accept these values but never return them. Account IDs and read tokens are public/plaintext; read tokens still gate gallery reads and tracking. Unique one-way fingerprints prevent read/admin token reuse across accounts and roles.

A separate deployment management secret authorizes account registration, credential/storage replacement, and account disable/re-enable. Account admin tokens authorize only their own galleries, catalog, ingestion, and schedule. Management authorization does not substitute for account authorization. Credentials are not logged or included in job error responses.

Each account has an S3 prefix. Signing/deletion validates that prefix, and orphan sweeps list only that prefix. Tenancy still depends on account-scoped SQL for gallery membership and content. Shared buckets must not have the same account namespace managed by multiple independent databases.

## Operational limits

The supported deployment is one active instance per database. Rate limiting keys on the edge-set client address header (`CF-Connecting-IP` by default, `CLIENT_IP_HEADER`), never on `X-Forwarded-For`, and falls back to the socket peer address. It assumes the origin accepts traffic only from that edge; a directly reachable origin lets callers forge the header. The optional bundled UI is disabled by default and stores entered tokens only for the browser tab. Presigned media URLs are bearer capabilities until expiry or storage-side revocation, including after an application account is disabled.

The local Garage config, local management token, and all-zero development encryption key are deliberately public examples. They must not be used in production. No development configuration or account registration file belongs in a jar or image. CI scans repository history for secrets and submits dependency information for vulnerability alerts.

See [operations](docs/operations.md) for safe credential rotation, recovery, and explicit database reset. No startup path deletes a database or empties a bucket.
