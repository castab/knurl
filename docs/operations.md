# Operations

## Deploy one instance

Build with `docker build -t knurl .` or use `castab/knurl:<version>`. The image runs one non-root JVM with a 512 MB heap and includes ffprobe. Provide PostgreSQL 18+, `DATABASE_URL`, a stable `CREDENTIAL_ENCRYPTION_KEY`, and exactly one management-token source. Supply secrets through your deployment platform; do not bake them into the image. Account and bucket credentials belong in the management API, not environment variables.

Run only one active Knurl instance per database. Stop the old instance before starting its replacement. Startup recovers unfinished jobs, so overlapping replicas are unsupported. Termination grace should accommodate an active remote call (up to five minutes); workers finish synchronous persistence boundaries before clients close. A forced kill leaves durable work for startup recovery and may leave prefix-scoped orphans.

Use a private bucket that already exists. Knurl requires GetObject, PutObject, DeleteObject/DeleteObjects, and ListBucket scoped to its account prefix wherever the provider supports that policy. It does not need bucket creation or bucket deletion permissions. Endpoint hostnames must be reachable by the application and by browsers consuming presigned URLs. HTTPS is required except loopback and the local Compose hostname `garage`.

A single shared bucket can hold multiple accounts and unrelated content. Only `knurl/accounts/{accountId}/` is managed. The same account namespace must not be managed by a second database.

## Garage for local development

`docker compose up -d` starts PostgreSQL and `dxflrs/garage:v2.5.0`. The checked-in `dev/garage.toml` and published keys are development defaults only. Garage uses a persistent `garage-data` volume, one node, region `garage`, and path-style requests at `http://localhost:3900`. Neither RPC nor a management API is published to the host.

The `--single-node --default-bucket` startup flags create the layout, default key, and private `knurl-media` bucket automatically. No init container is needed. Check health with:

```sh
docker compose exec garage /garage health
```

Normal application operation never creates buckets. For isolated integration tests that create their own temporary buckets, explicitly allow that on the development key:

```sh
docker compose exec garage /garage key allow --create-bucket GK00000000000000000000000000000000
```

Create another bucket/key with Garage's native CLI and grant its read/write access if you want to exercise independently owned storage. Follow the [Garage quick start](https://garagehq.deuxfleurs.fr/documentation/quick-start/) for those administrative commands. Use a properly replicated Garage deployment or another S3 provider for production.

## Register, inspect, and rotate

The README contains a full registration example. Management routes require `Authorization: Bearer <management token>`. The account ID is immutable. Read/admin tokens must contain at least 16 characters, no whitespace, and be unique across every account and both roles. The management secret must contain at least 16 characters.

`PATCH /api/v1/management/accounts/{accountId}` accepts any supplied credential replacements, `enabled`, or a complete replacement `storage` object. Omitted fields remain unchanged. Instagram access-token changes require the matching future expiration timestamp. Callers supply long-lived tokens; Knurl refreshes them when within 24 hours of expiry.

Responses expose public configuration, the read token, and indicators that secret credentials are present. They never return an admin token, Instagram token, or S3 secret key. Keep your own recoverable copies if needed. Plaintext secrets are encrypted before binding database writes.

Admin/read token changes affect subsequent requests immediately. Replacing an Instagram token increments its version and wins against older in-flight refreshes. To rotate S3 keys, retain the same bucket/region/endpoint/addressing configuration and provide the complete storage object with new credentials. New operations use the new client context; an already-running operation retains its lease. Coordinate provider-side revocation accordingly. Cached signed URLs from an old credential can remain valid until expiry or provider revocation.

Management-token files are re-read on every management request. Change the mounted file atomically. `CREDENTIAL_ENCRYPTION_KEY` must remain stable across restarts: changing it does not re-encrypt stored data. Keep it backed up outside the database. There is no online encryption-key migration tool in this iteration. If a key is lost, restore it or re-provision the affected secret credentials using the management API; write-only APIs cannot recover them for you.

## Storage destination changes

Bucket name, region, endpoint, and addressing style identify the destination. Changing any of these is rejected while a job is queued/running, a storage lease is active, known media exists, or an upload may have left remote objects. A possible-object marker is written before upload. It is cleared only when maintenance confirms both an empty known-key set and an empty account prefix.

This deliberately prevents an apparently failed upload from being stranded by a destination edit. An empty-known-set/nonempty-prefix condition triggers the orphan safety guard and requires operator investigation. Automatic bucket moves are unsupported; copying objects manually and changing configuration is not a supported migration procedure.

## Scheduling and results

An account starts with scheduling disabled and no initial job. PUT this body to its `/ingestion/schedule` endpoint using its admin token:

```json
{ "enabled": true, "intervalSeconds": 900 }
```

Intervals range from 300 seconds to one year. Enabling queues an immediate job; subsequent runs occur one interval after a terminal result, including a failure or manual job. Changing an idle interval resets the next due time; changing an active interval applies after completion. Disabling a schedule leaves active work running. Missed intervals do not accumulate catch-up jobs.

POST to `/ingestion/jobs` returns `202` and its status Location. Repeated requests return the same active job rather than queuing duplicates. GET a job for its summary and a page of work outcomes; follow pagination links for all results. `SUCCEEDED` means the full feed and associated media/retention work finished. `PARTIAL` means feed enumeration succeeded but other work failed; `FAILED` includes unsuccessful feed enumeration or another unrecoverable job error. `SKIPPED` work covers unsupported/unselected media. After three attempts, missing media URLs are explicit failures, not successful downloads.

Error codes describe the operation without upstream bodies or token-bearing URLs. Check `INSTAGRAM_CREDENTIAL_REJECTED`, `STORAGE_CREDENTIAL_REJECTED`, `MEDIA_NOT_READY`, and `SUSPICIOUS_FEED_REDUCTION` before manually queuing another job. Completed history expires after 30 days; active jobs are retained. No job-cancellation endpoint is supplied; disabling the account requests cancellation.

Disabling an account blocks its gallery/admin APIs immediately, cancels queued jobs, and durably requests safe cancellation of running work. Existing rows and bucket objects remain. Management still has inspection/recovery access. Re-enable to restore access; an enabled schedule resumes immediately. Permanent account deletion is deferred.

## Maintenance

Retention and orphan policy stay in `sync_configurations`:

| Key | Default |
| --- | --- |
| `retention_days` | 30 |
| `orphan_sweep_interval_hours` | 24 |
| `orphan_grace_minutes` | 60 |
| `orphan_sweep_max_deletes` | 1000 |
| `orphan_sweep_dry_run` | false |
| `vanished_media_max_percent` | 50 |

Orphan sweep timestamps live on each account. Maintenance is independent of feed schedules and skips disabled accounts. It never lists or deletes outside that account's prefix. The empty-known-set guard refuses deletion after a database reset/mismatch. Turn on dry-run when investigating orphan behavior. Database row deletion precedes bucket deletion; durable deletion manifests and later orphan sweeps repair interrupted cleanup.

HTTP rate limits are per client IP. The expected deployment puts Cloudflare in front of Knurl, and the limiter keys on `CF-Connecting-IP`, which Cloudflare sets to the connecting client and overwrites if a caller sends their own. `X-Forwarded-For` is not consulted. Set `CLIENT_IP_HEADER` to another single-address header for a different edge, or to an empty value when clients connect directly; when the header is absent the socket peer address is used.

The header is trustworthy only if the origin is unreachable except through Cloudflare. Restrict origin access (Cloudflare Tunnel, authenticated origin pulls, or an allowlist of Cloudflare's address ranges); otherwise a caller who reaches the origin directly can send any value and choose their own rate-limit bucket. Terminate TLS at the edge.

`GET /health` is unauthenticated and returns `200 {"status":"ok"}` when the database answers, otherwise `503`. The result is cached for five seconds. The image's `HEALTHCHECK` calls it on `PORT`; it does not report ingestion progress, so use the job endpoints for that. Existing signed read URLs can outlive application token rotation/disable until their signature expires or bucket credentials are revoked.

## Breaking upgrade / clean reset

This iteration intentionally replaces V1/V2 with one new baseline. It does not upgrade the old schema. Never repair Flyway checksums to run old tables against new code. Stop both old services, explicitly reset a database dedicated to Knurl, then start the new image and register accounts again. No startup path performs a destructive reset.

For a disposable local Compose stack only, `docker compose down -v` deletes that stack's PostgreSQL and Garage volumes. When replacing an older MinIO stack, its old volume may remain and must be handled explicitly by its owner. Knurl does not migrate it.

For other environments, have the operator reset only the dedicated Knurl database/schema and remove the old Knurl-managed `posts/` and `catalog/` objects as appropriate. Never empty an account-holder bucket containing unrelated files. The new version manages only its new account prefixes; it will not sweep old global prefixes. Replacing the application does not justify deleting unrelated account data.

## Integration tests

Unit tests run with `sh gradlew build`. Integration suites are opt-in and always rerun when enabled. Start the development dependencies and grant the test key bucket-creation permission as shown above, then:

```sh
DATABASE_INTEGRATION_TEST=true S3_INTEGRATION_TEST=true sh gradlew test --no-daemon
```

On PowerShell set `$env:DATABASE_INTEGRATION_TEST='true'` and `$env:S3_INTEGRATION_TEST='true'`, then run `.\gradlew.bat test --no-daemon`.

Override `TEST_DATABASE_URL`, `TEST_S3_ENDPOINT`, `TEST_S3_REGION`, `TEST_S3_ACCESS_KEY`, or `TEST_S3_SECRET_KEY` for other disposable infrastructure. Defaults match Compose. PostgreSQL tests create/drop only a unique test schema. Storage-isolation tests create/delete unique test buckets; the basic signing test creates a uniquely named object in `knurl-media` and removes that object afterward. Do not target production credentials.

Coverage includes ciphertext storage, cross-account tokens, atomic rotations, concurrent enqueue, scheduling, durable recovery, destination locks, shared/separate buckets, private vs signed reads, sibling/unrelated-object safety, and versioned signer leases.
