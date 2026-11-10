# Architecture

## One runtime, explicit boundaries

`knurl-app` owns one HTTP listener and supervised ingestion/maintenance coroutines. `shared-domain` contains framework-free models, JDBI repositories, database configuration, and encryption. Construction is manual; there is no application framework or DI container. Flyway runs before the listener or workers start.

HTTP handlers record curation and ingestion intent. They do not upload or delete media synchronously. A single account worker drains durable jobs; another coroutine performs bounded maintenance. Blocking database, Graph API, decoding, and S3 work run on the shared IO dispatcher. Two semaphore permits bound concurrent media work, and batches contain at most two items. Waiting accounts remain database rows rather than live coroutines.

Video payloads stream from OkHttp to S3 and ffprobe. Unknown lengths spill to a counted, size-limited temporary file. Full media is never buffered as a byte array. Images retain the bounded compressed-output exception and existing 400px square/800px detail variants. The deployment heap is 512 MB and Hikari retains only two connections maximum, one idle minimum.

## Accounts and credentials

`instagram_accounts` holds identity, enabled state, scheduling, storage configuration, and encrypted credentials. The same `CredentialCipher` encrypts Instagram access tokens, account admin tokens, and S3 secret access keys with AES-256-GCM and a fresh random IV per write. Read tokens and account IDs remain public/plaintext. A separate token-fingerprint table enforces a unique namespace across read and admin roles.

Management authorization uses an independent operator-supplied secret outside the database. Account authorization reads current database credentials on each request; there is no stale process-wide credential snapshot. A disabled account is unavailable to account-scoped routes, while management can still inspect/re-enable it.

Instagram refresh occurs when expiry is within 24 hours. A version-checked update prevents an old refresh from overwriting a concurrent management replacement. Secret-bearing models and request objects do not generate value-revealing `toString()` output. Responses and job errors never contain secret values or upstream response bodies.

## Durable ingestion

A partial unique index permits one `QUEUED`/`RUNNING` job per account. Account-row locks serialize job creation, schedule changes, and destination changes; no transaction remains open over network or media processing. Manual and scheduled requests coalesce into an existing active job.

`ingestion_work` tracks phase/item status, attempt count, next retry, sanitized errors, and deletion manifests. A successful feed writes its complete ID snapshot into `ingestion_feed_items`. Later phases use this durable snapshot; a failed or interrupted pagination sweep cannot cause vanished-media deletion. Download URLs are fetched fresh and are not persisted.

Jobs progress through feed, vanished-media handling, catalog thumbnails, selected downloads, and retention. Thumbnails/downloads are explicitly successful, skipped, retryable, or failed. An absent media URL is retryable until exhausted, rather than being mislabeled as downloaded. Known unsupported media and items no longer selected are skips.

Transient operations get three total attempts, with 5/30-second backoffs and bounded Retry-After support. Failed feed enumeration yields `FAILED`; other exhausted phase/item failures yield `PARTIAL`. Successful or skipped work is reused after restart. A crashed attempt consumes its recorded attempt; exhausted work is reported rather than retried forever. Startup recovery preserves job IDs and budgets.

Account disable records durable cancellation intent even if it is re-enabled before the worker reaches a safe boundary. In-flight synchronous uploads may finish and record their rows before the next cancellation checkpoint. Shutdown cancels worker scopes before closing clients and the datasource. Allow enough termination grace for a bounded remote operation to finish; forcibly killed uploads may leave recoverable orphans.

## Account-owned storage

Each account defines an existing private S3-compatible bucket, region, optional endpoint, addressing mode, and static credentials. Uploads, signed reads, and deletions use that account's settings. No deployment-wide bucket credential or bucket fallback exists. Garage is the local development backend; the application uses standard AWS SDK operations. The client uses WHEN_REQUIRED request checksums to avoid optional checksum trailers that Garage rejects, while retaining required checksums and streaming uploads. See the [AWS checksum configuration](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/s3-checksums.html).

Every managed key is under `knurl/accounts/{accountId}/`. Catalog thumbnails use `catalog/{mediaId}/thumbnail.webp`; downloaded variants use `posts/{mediaId}/{position}/{small.webp|large.webp|original}`. Bucket names and endpoints are stored as account configuration; media rows store keys and metadata, never signed URLs.

Accounts may share buckets. Orphan listing uses an S3 prefix filter, verifies returned keys again, and compares only that account's known objects. Read signing and deletion reject keys outside the prefix. Unrelated files and sibling prefixes are never candidates. The same `(bucket, account prefix)` must belong to only one Knurl database; distinct deployments must not independently manage an identical namespace.

A bounded cache holds at most 16 versioned S3 client/presigner contexts, each with at most 1024 signed URL entries. Leases keep clients alive through an active operation. Rotation creates a new context for subsequent operations, while old operations can finish. Already-issued URLs expire according to their signature or provider credential revocation.

Destination changes are blocked by queued/running jobs, active storage leases, known object references, or possible uploaded artifacts. `storage_used` is set before an upload attempt, since a failed upload may still have created an object. A successful maintenance listing can clear that marker only when both the database's known set and the account prefix are empty. Bucket moves and automatic copies are intentionally unsupported.

## Media lifecycle

Gallery visibility is per-gallery; retention protection is membership in any gallery. Membership mutations lock catalog rows before changing the denormalized deletion clock. Retention also reads `COALESCE(deselected_at, post.created_at)` so an inconsistent clock cannot leak media forever. Explicit purge overrides pinning; ordinary retention respects it.

Deletion manifests are persisted before deleting database rows, then bucket deletion follows. Retention rechecks membership when deleting the post; a concurrent re-add wins. Manifests survive failures/restarts so S3 retries do not lose their keys. Prepared vanished-media manifests additionally verify that post/catalog deletion committed before deleting bytes. Catalog rows and browse thumbnails survive an ordinary media purge.

Vanished-media removal runs only after complete pagination and retains the suspicious-reduction guard. It removes catalog entries and memberships because Instagram is the source of truth. Gallery names affected by source removal are logged for operator visibility.

Orphan sweeping remains independent of account schedules, with a per-account timestamp, default 24-hour cadence, 60-minute grace, 1000-deletion cap, dry-run option, and refusal to delete when known keys are empty. Disabled accounts are retained and are not swept. Global retention/sweep policy knobs remain in `sync_configurations`; only schedules and sweep timestamps are per-account.

## Superseded directives

This iteration deliberately replaces the separate HTTP/ingestion process boundary, ingestion-only schema ownership, credential-free account registry, in-memory-only read/admin credentials, external credential authority, global S3 settings, one-bucket-per-database constraint, and two published images. It also replaces the previous migration history once, with an explicit reset. Subsequent schema changes are forward-only. Streaming limits, tenancy checks, manual wiring, pagination conventions, and deletion safeguards remain requirements.
