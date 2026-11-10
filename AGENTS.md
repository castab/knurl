# AGENTS.md — Knurl

Read before structural changes. This iteration deliberately consolidates the former ingestion and presentation services into one application; the user authorized the changes listed under Superseded rules. [Architecture](docs/architecture.md) and [operations](docs/operations.md) describe the current system.

## Runtime and low-memory streaming

- `knurl-app` is the only executable, with manually wired HTTP, ingestion, and maintenance collaborators. `shared-domain` is a library with no HTTP/JSON/application-framework dependencies. Do not add Spring, a DI container, or serialization annotations to domain models.
- Support **one active process per database**. The container heap is **512 MB**, Hikari maximum pool size **2**/minimum idle **1**, and there is one account ingestion worker. Persist queued requests rather than starting a coroutine per request/account. Blocking work uses shared `Dispatchers.IO`; batches and shared media permits are bounded to two. Do not add dedicated or unbounded executors/concurrency.
- Never buffer a full media payload in `ByteArray`/`ByteArrayOutputStream`. Known-length video streams from OkHttp directly to AWS `RequestBody`, mirrored to ffprobe. Unknown-length video spills to a local temporary file using counted `copyWithLimit`, then uploads from disk. Keep the 100 MiB declared-length and counted-copy limits.
- Image decoding necessarily retains a raster. Keep the declared `MAX_SOURCE_IMAGE_BYTES` ceiling and bounded compressed-output exception. Retain only the existing 400x400 scale/crop grid variant and aspect-preserving, never-upscaled 800px detail variant, at 80% WebP quality. Do not add image variants without checking memory cost.
- Shutdown stops HTTP/new work and cancels workers before closing clients/data sources. A cancellation must not be swallowed by broad failure handling or classified as an ordinary retry.

## Account and credential authority

- The database owns all account configuration. There are no LOCAL/HTTP credential modes, external broker callbacks, global account seeds, or global bucket credentials.
- `instagram_accounts` stores public account IDs/read tokens and encrypted Instagram access tokens, admin tokens, and S3 secret access keys. Use the existing AES-256-GCM `CredentialCipher`, with random IVs and an external `CREDENTIAL_ENCRYPTION_KEY`. Never bind those secret values as plaintext SQL parameters. Never log or return secret-bearing models, request bodies, upstream bodies, or token-bearing URLs.
- Secret-bearing inputs/configuration must not have generated value-revealing `toString()` output. Account responses contain only public configuration, credential-presence indicators, and expiry.
- The separate deployment management credential authorizes registration, replacement of account/storage credentials, and account enablement. It is supplied by one typed-config source (value or re-read-per-use file). It does not authorize account routes.
- Account admin/read authorization reads current database values, is scoped to the URL account ID, and compares in constant time. Keep route dependencies on `VerifyAccountToken`, not a credential store. Unknown/disabled account => 404; mismatched token => 401. Read tokens remain required for gallery reads/tracking.
- Read/admin fingerprints are globally unique across both roles; public read credentials must never double as admin credentials. Token changes and fingerprint updates are one transaction.
- Instagram refresh is account-parameterized and only near expiry. Version-checked persistence prevents an old refresh from overwriting management rotation. There is no environment/database credential fallback.

## Storage isolation and lifecycle

- Every account has its own bucket/region/endpoint/addressing mode/access credentials. Shared buckets are allowed. Every object belongs under `knurl/accounts/{accountId}/catalog/{mediaId}/...` or `knurl/accounts/{accountId}/posts/{mediaId}/{position}/...`.
- Upload, signing, deletion, known-object queries, and sweep timestamps are account-scoped. Listings must include the exact prefix and returned keys must be checked again. Never sweep an entire account-holder bucket. Identical `(bucket, account prefix)` namespaces must not be independently managed by two databases.
- Versioned S3 client leases must survive active operations during key rotation, close unused resources, and remain bounded. URL caches are also bounded; no account/version may inherit another's signer or signed URLs.
- Changing a destination is forbidden while queued/running jobs, active storage leases, known media, or possible uploaded artifacts exist. `storage_used` is set before upload and clears only after maintenance confirms no known or remote objects. Bucket migration is unsupported. Credential rotation within the same destination is allowed.
- HTTP curation records intent; ingestion performs uploads and byte deletions. Media-purge endpoints retain 202/acceptedCount semantics. Combining processes does not make synchronous bucket deletion in a request handler acceptable.
- A gallery shows exactly its own members. Membership in any gallery protects downloaded media from retention. Removing the final membership starts `deselected_at`; re-adding clears deselection/purge clocks. Explicit purge defeats pinning; ordinary retention does not.
- Membership changes lock catalog rows first. Preserve the retention query's `COALESCE(deselected_at, post.created_at)` fallback; both defend eventual cleanup and concurrent last-membership removal.
- Delete database rows before S3 objects, rechecking membership in retention's delete statement. Persist deletion manifests before row removal so retries retain keys. Failed cleanup leaves an orphan rather than a surviving row pointing to missing media.
- Vanished-media removal uses only a complete feed snapshot, retains the suspicious-reduction guard, and logs affected curated gallery names. Never treat failed or partial pagination as authoritative absence.
- Orphan sweeping keeps its age grace, empty-known-set refusal, deletion cap, and dry-run toggle. Listings are paged; do not retain a second complete bucket listing alongside known keys. Disabled accounts retain their data and are not swept.

## Durable jobs and scheduling

- A partial unique index permits at most one queued/running job per account. Enqueue/scheduling/destination changes take short account-row locks; never hold a transaction over remote I/O.
- Jobs and work items persist attempts, retry times, outcomes, and complete feed IDs. Success requires feed, thumbnails, selected downloads, vanished cleanup, and retention to finish; an empty queue or missing media URL is not success.
- Three total attempts bound transient retries; credential/permanent failures terminate work. Preserve sanitized failure codes, explicit skips, partial job results, startup recovery with stable IDs, and the 30-day terminal-history limit.
- Accounts start unscheduled, with no initial ingestion. Enabling a schedule queues immediately; intervals are at least five minutes, default fifteen. Cadence is measured after terminal completion. Manual triggers coalesce; missed intervals do not create catch-up backlogs.
- Disabling a schedule lets a current job finish. Disabling an account revokes access and durably requests safe cancellation, including across a quick re-enable or restart. There is no permanent account deletion yet.
- Retention/sweep policy remains global in `sync_configurations`; per-account schedules and sweep clocks do not imply per-account retention knobs.

## Database, HTTP, and configuration

- Post/media IDs remain globally unique natural keys. Surrogate post IDs come from PostgreSQL 18's `uuidv7()`, never application invention. Kotlin IDs use `kotlin.uuid.Uuid`; reuse the registered JDBI bridge. Where scalar mapper inference is ambiguous, explicitly parse a result string or use the existing `UuidRow` wrapper.
- Gallery membership SQL must join both gallery and catalog to the same account. Global shortcodes do not provide a composite foreign-key backstop; keep tenant tests.
- The consolidation replaces V1/V2 once and requires an explicit operator reset. **After this baseline, migrations are forward-only**: add the next version, never rewrite an applied migration. Startup must never reset/clean a database or bucket.
- Load one typed Hoplite configuration. First property source wins: packaged `application.conf` first, optional filesystem `config/application-local.conf` second. Only the resource file ships. Real env substitutions must outrank local defaults; do not scatter `System.getenv` calls through runtime code.
- Non-loopback database URLs require TLS unless the URL explicitly sets sslmode; preserve the existing parser behavior.
- Keep `SecurityHeaders` outside the rate limiter so even 429 responses carry headers. UI defaults off. CSP permits cross-origin media for account-holder buckets; new scripts/styles remain same-origin. Rate limiting assumes one process and keys on the edge-set `CLIENT_IP_HEADER` (Cloudflare's `CF-Connecting-IP` by default; peer address otherwise); never key on `X-Forwarded-For`. `/health` stays unauthenticated, cached, and free of deployment detail.
- Declare routes through http4k contracts, KotlinxSerialization body lenses, no-op documented bearer security, and inline account-aware authorization. Remember positional parameters for literal segments after path lenses. Jackson remains the OpenAPI renderer because KotlinxSerialization cannot reliably walk its schema examples.
- Lists use `{data,pagination:{totalRecords,currentPage,totalPages,links}}`; cap sizes at 50, preserve filters in relative links, report effective clamped pages, use total order/tiebreakers, and apply identical count/row filters. Keep OpenAPI examples fully populated despite nullable runtime fields.

## Verification and release

- Kotest FunSpec + MockK are the standard; do not add hand-rolled fake/recording collaborators. Preserve meaningful media and tenant regression tests when moving code.
- `sh gradlew build --no-daemon` runs compilation/lint/unit tests. Live PostgreSQL/Garage tests are gated by `DATABASE_INTEGRATION_TEST`/`S3_INTEGRATION_TEST`, use isolated schemas/buckets, and must not reset operator data. See operations for test key permissions.
- Route changes require regenerating and committing `knurl-app/openapi.json` using `:knurl-app:generateOpenApiSpec`; offline and production wiring must match.
- Docker Compose provides PostgreSQL and community Garage for development; never package `dev/garage.toml`, dev config, or local account JSON into the application image.
- Publish only `castab/knurl`. Stable annotated `vX.Y.Z` tags produce X.Y.Z/X.Y/X/latest; RC annotated `vX.Y.Z-rc.N` tags produce only the exact RC and a prerelease. Preserve immutable tags, main-ancestry validation, and changelog-based notes. Never create/push tags or publish without an explicit request.

## Superseded rules

The user explicitly authorized replacing separate process boundaries, ingestion-only migrations, credential-free account records, in-memory presentation credentials, external credential authority, deployment-global S3 settings, one bucket per database, and two-image releases. A one-time schema baseline reset was also authorized. These changes do not relax streaming, tenant isolation, lifecycle, secret-redaction, or bounded-resource requirements.
