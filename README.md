# Knurl

Knurl is a low-memory Instagram media aggregation engine written in Kotlin. It's a monorepo of two long-running JVM services that share a Postgres database and an S3-compatible object bucket, with multi-account support built in: `ingestion-service` is account-agnostic — one process (or several, sharing one database) discovers and processes *any* registered Instagram account, claimed via Postgres row locking rather than being pinned to one account per process — and a single `presentation-service` deployment serves/manages every one of those accounts:

- **`shared-domain`** — Flyway migrations, JDBI repositories, connection pool setup, and the plain data classes both services depend on.
- **`ingestion-service`** — a daemon (or cron-triggered, one-shot process) that claims due accounts and pending downloads from the shared database (see [Account-agnostic ingestion](#account-agnostic-ingestion) below), catalogs each account's entire media feed, downloads/stores the posts an admin has curated into a gallery, enforces retention, and sweeps orphaned objects out of the bucket. One process is enough for any number of accounts; running several is for throughput/redundancy, not a requirement for multi-account support.
- **`presentation-service`** — a multi-tenant gallery API (built on [http4k](https://www.http4k.org)) that serves registered accounts' named galleries as JSON with S3 presigned URLs behind a shared Bearer token, plus analytics tracking and a per-account-authenticated admin API for curating what gets synced.

`ingestion-service` is an ephemeral sync job with a **512MB heap** (`-Xmx512m`, set in its Dockerfile); `presentation-service` retains a 128MB deployment target. Both services stream remote assets rather than buffering them, regardless of that capacity — see [`AGENTS.md`](./AGENTS.md) for the exact rules. Allocate at least 1 GiB of container memory to ingestion for JVM native memory, thread stacks, and `ffprobe` in addition to its Java heap.

Neither service uses an application framework (no Spring Boot, Micronaut, Ratpack, DI container). Each is a plain `fun main()` that manually constructs and wires its own collaborators.

## Requirements

- JDK **25**
- **PostgreSQL 18+** — the schema migration uses Postgres's native `uuidv7()` function for primary keys, which doesn't exist before Postgres 18
- No Gradle install needed — use the bundled wrapper (`./gradlew`, Gradle 9.6.1)

## Tech stack

| Concern | Choice |
|---|---|
| Language | Kotlin 2.4.20, JVM 25 |
| HTTP server | http4k 6.58.0.0 (Undertow backend, `http4k-api-openapi` for OpenAPI docs + Swagger UI at `/docs`) |
| HTTP client | OkHttp 4.12.0 (`ingestion-service` only — for the Graph API and CDN downloads. `presentation-service` makes no outbound HTTP calls: presigning is a purely local signing operation.) |
| JSON | kotlinx.serialization for all route bodies; Jackson only backs the OpenAPI schema renderer (a documented http4k limitation - see `AGENTS.md`) |
| Config | [Hoplite](https://github.com/sksamuel/hoplite) loading layered HOCON `.conf` files (`application.conf` merged with local dev overrides), with optional environment variable substitution via `${?VAR}` |
| Database access | JDBI3 + HikariCP |
| Migrations | Flyway |
| Object storage | AWS SDK v2 (`S3Client`, `S3Presigner`), against any S3-compatible provider (e.g. Railway Buckets) |
| Image processing | Scrimage → WebP; ffprobe reads stored-video dimensions from the source container stream |
| Testing | Kotest |
| Lint/format | Kotlinter (ktlint) |

## Environment variables

Each service reads its config from a bundled `application.conf` (HOCON), which pulls these in via environment variables (`${?VAR}`) for deployed environments. `application.conf` is the **only** config file packaged into the jar and image; the `config/application-local.conf` / `config/application-instagram.conf` overrides described below are read from disk during local development and never ship. A variable marked **required** has no default anywhere, so in a deployment it must come from the environment or the service fails fast at startup.

| Variable | Required | Used by | Purpose |
|---|---|---|---|
| `DATABASE_URL` | yes | both | Postgres connection string. Accepts either `postgres://user:pass@host:port/db` (Railway/Heroku style, auto-converted) or a `jdbc:postgresql://...` URL. |
| `S3_BUCKET_NAME` | yes | both | Target bucket for media and presigned URLs. |
| `S3_REGION` | yes | both | AWS region (or the region your S3-compatible provider expects). |
| `S3_ACCESS_KEY_ID` | yes | both | Bucket credentials. |
| `S3_SECRET_ACCESS_KEY` | yes | both | Bucket credentials. |
| `S3_ENDPOINT` | no | both | Endpoint override for non-AWS S3-compatible providers (e.g. Railway Buckets). |
| `S3_PATH_STYLE_ACCESS` | no | both | Set `true` if your provider requires path-style bucket addressing. Default `false`. |
| `S3_PRESIGNED_GET_TTL_SECONDS` | no | presentation | How long issued gallery media URLs (each `mediaItems[].smallUrl`/`largeUrl`/`videoUrl`) remain valid before S3 rejects them. Default `21600` (6h) — long enough for interactive gallery browsing; each media item also carries its own `mediaUrlExpiresAt` timestamp. |
| `PORT` | no | presentation | HTTP port. Default `8080`. |
| `UI_ENABLED` | no | presentation | Set to `true` to enable the built-in gallery/admin UI served at `/` (the JSON API, `/docs`, and `/openapi.json` are unaffected either way). **Default `false`** - the UI has no login of its own, so a deployment must opt in rather than serve an admin surface by accident. It's a thin client that asks the operator to paste tokens, holds them in `sessionStorage`, and sends them as Bearer headers. |
| `RATE_LIMIT_PER_MINUTE` | no | presentation | Requests allowed per client IP per rolling minute, applied to every route. Default `120`. Keyed by the first hop of `X-Forwarded-For`; clients behind no proxy (or a proxy that doesn't set it) share one bucket. |
| `CREDENTIALS_MODE` | no | both | `LOCAL` (default) or `HTTP` — see [Credential modes](#credential-modes). The same variable, with the same meaning, in both services. |
| `CREDENTIALS_HTTP_URL` | HTTP mode | both | Exact endpoint this service fetches its credentials from. **Different value per service**: ingestion points at the per-account Instagram-token endpoint, presentation at the endpoint listing every account's token pair. HTTPS is required unless loopback is used or plaintext is explicitly enabled. |
| `CREDENTIALS_AUTHORIZATION_TOKEN` | HTTP mode | both | Pre-shared secret authorizing this service's credential fetches. On presentation it is also the secret required on the control plane's `PUT /api/v1/admin/credentials/refresh` signal — one secret for one trust relationship, in both directions. Configure this or the file variant, never both. |
| `CREDENTIALS_AUTHORIZATION_TOKEN_FILE` | HTTP mode | both | File containing that secret. Reread on every use, so a rotating mounted workload credential takes effect without a restart. |
| `CREDENTIALS_HTTP_ALLOW_PLAINTEXT` | no | both | Set `true` only when the credential endpoint is reached over a trusted private service network. Default `false`; loopback HTTP is always allowed for local development. |
| `INSTAGRAM_ACCESS_TOKEN` | LOCAL mode | ingestion | Long-lived Instagram Graph API access token seeding database-backed refresh for `INSTAGRAM_BUSINESS_ACCOUNT_ID` only - see [Account-agnostic ingestion](#account-agnostic-ingestion). Unused in `HTTP` mode, where the control plane owns refresh. |
| `INSTAGRAM_BUSINESS_ACCOUNT_ID` | yes / LOCAL mode | ingestion (always), presentation (LOCAL mode) | The Instagram Business Account ID. Public, non-secret - it's also the `{accountId}` path segment on presentation-service. ingestion anchors this one account's row at startup in either mode; presentation uses it in `LOCAL` mode as the account `ADMIN_TOKEN`/`READ_TOKEN` apply to. Not a restriction on which accounts ingestion's workers claim - see [Account-agnostic ingestion](#account-agnostic-ingestion). |
| `ADMIN_TOKEN` | LOCAL mode | presentation | Operator-chosen secret (not an Instagram credential) gating `INSTAGRAM_BUSINESS_ACCOUNT_ID`'s admin catalog/selection API. Held only as a SHA-256 hash in memory; change and restart to rotate. Unset it in `HTTP` mode, where the control plane owns the token pair; setting it there is ignored with a startup warning. |
| `READ_TOKEN` | LOCAL mode | presentation | Operator-chosen secret gating that same account's public gallery-read API - distinct from `ADMIN_TOKEN` and, since it is shipped to browsers, never a substitute for it. Same handling, and same `HTTP`-mode warning. |
| `CREDENTIAL_ENCRYPTION_KEY` | LOCAL mode | ingestion | Base64-encoded AES-256 key (32 raw bytes) used to encrypt the Instagram access token before it is persisted to `auth_config.access_token_encrypted`. Generate one with `CredentialCipher.generateKey()`. Rotating it makes any already-stored token undecryptable - delete that account's `auth_config` row (or ensure `INSTAGRAM_ACCESS_TOKEN` is set fresh) after rotating. Unused in `HTTP` mode, where the control plane owns the token and ingestion never reads or writes `auth_config`; setting it there is ignored with a startup warning. |
| `INSTAGRAM_API_VERSION` | no | ingestion | Graph API version. Default `v21.0`. |
| `RUN_ONCE` | no | ingestion | `true` to have every worker loop drain to "nothing currently claimable" and exit, instead of polling indefinitely (for cron-triggered deployment). Default `false`. |
| `INGESTION_INTERVAL_SECONDS` | no | ingestion | How stale an account's last successful sync must be before it's due again. Default `900`. Per-account, not a global cadence. **Only applies to the persistent-daemon case** (`RUN_ONCE=false`) - a `RUN_ONCE` invocation always treats every account as due, since the operator's own trigger (schedule or manual) is the real cadence control there. See [Account-agnostic ingestion](#account-agnostic-ingestion). |
| `FEED_SYNC_CONCURRENCY` | no | ingestion | Concurrent feed-sync worker coroutines, each claiming and syncing one due account at a time. Default `1`. |
| `DOWNLOAD_WORKER_CONCURRENCY` | no | ingestion | Concurrent download-worker coroutines, each claiming and processing one batch of pending downloads at a time. Default `1`. |
| `DOWNLOAD_CLAIM_BATCH_SIZE` | no | ingestion | Max pending-download items one worker claims per claim statement. Default `5`. |
| `DOWNLOAD_CLAIM_LEASE_SECONDS` | no | ingestion | How long a claimed download is honored before another worker treats it as abandoned and reclaims it. Default `300`. |
| `ACCOUNT_SYNC_LEASE_SECONDS` | no | ingestion | Same, for a claimed account's feed sync. Default `600`. |
| `COMPLETED_DOWNLOAD_RETENTION_HOURS` | no | ingestion | How long a completed download's `pending_downloads` row is kept (for operator visibility) before a periodic sweep purges it. Default `24`. |
| `CLAIM_POLL_INTERVAL_MS` | no | ingestion | Sleep between claim attempts for any worker loop that found nothing to claim. Default `5000`. |
| `DB_MAX_POOL_SIZE` / `DB_MIN_IDLE` / `DB_IDLE_TIMEOUT_MS` | no | both | HikariCP pool overrides. Defaults (`2` / `1` / `30000`) preserve the low-memory connection footprint. Claims are fast claim-and-release statements, not held transactions, so raising worker concurrency does **not** require a matching pool-size increase the way a held-transaction design would - a modest pool comfortably serves several concurrent workers. Don't raise these without reviewing the heap budget regardless. |

**Storage privacy:** `S3_BUCKET_NAME` must be provisioned as a *private* bucket — no public-read bucket policy, no anonymous `s3:GetObject` grant, and no public-access-block override that would allow unsigned requests to succeed. Neither service ever sets an object ACL on upload (`ingestion-service`'s `PutObjectRequest` calls set only `bucket`/`key`/`contentType`), so object visibility is entirely inherited from the bucket's own default (private) configuration. The only supported read path is a presigned GET minted by `presentation-service`; an unsigned request to the plain object URL must return `403`/access-denied. If your provider's default differs (or you're reusing a bucket that previously had public access enabled), disable public access explicitly in that provider's console/IaC before deploying.

**Token exposure:** `READ_TOKEN` guards browser-called gallery reads and `POST .../galleries/{galleryId}/track` for one account, so any real browser client must ship it to the browser. Treat it as an access gate against casual abuse, not strong authentication — it is not a secret once a gallery page uses it, and a leaked read token exposes only that one account's galleries, never another's. A per-account `ADMIN_TOKEN` is a genuine secret: it is never embedded in a page, and the bundled UI keeps both tokens only in `sessionStorage` for the life of the tab. `RATE_LIMIT_PER_MINUTE` backs this with an actual enforced limit, not just an honor system.

### Credential modes

`CREDENTIALS_MODE` is one flag with one meaning in both services: where the credentials they use and accept come from. It defaults to `LOCAL`, so a standalone deployment needs no extra configuration.

**`LOCAL`** — Knurl is self-sufficient, with no external dependency. `presentation-service` accepts `ADMIN_TOKEN`/`READ_TOKEN` for `INSTAGRAM_BUSINESS_ACCOUNT_ID`, hashing both into memory at startup. `ingestion-service` seeds its Instagram token from `INSTAGRAM_ACCESS_TOKEN` for that same account, refreshes it against the Graph API when fewer than 24 hours remain, and stores the result AES-GCM-encrypted in `auth_config`. Every other account a worker claims must already have its own `auth_config` row.

**`LOCAL` mode is deliberately single-account.** One `ADMIN_TOKEN`/`READ_TOKEN` pair cannot safely span tenants — a token valid for one account's galleries must never read another's. Serving several accounts is what `HTTP` mode is for.

**`HTTP`** — a control plane owns all credential authority, and Knurl mints, rotates and decides nothing. `presentation-service` fetches every account's token pair from `CREDENTIALS_HTTP_URL` before it binds a port, hashes them into memory, and anchors an `instagram_accounts` row for each — which is how an account the control plane provisions becomes one this deployment can create galleries for and will start syncing. `ingestion-service` requests a currently valid Instagram token per account as its workers claim work for it, verifies the response belongs to the account it asked about, and never reads or writes `auth_config`. A failed fetch never falls back to local credentials.

Rotation is signalled, not pushed: the control plane sends a bodyless `PUT /api/v1/admin/credentials/refresh` (bearing `CREDENTIALS_AUTHORIZATION_TOKEN`) and `presentation-service` re-fetches the authoritative set itself, replacing it wholesale — a per-account update could not express that an account had been removed. That route exists only in `HTTP` mode. Until a fetch succeeds the previous credentials stay live, so a control-plane outage degrades to stale data rather than a total denial.

The two endpoints Knurl expects:

```
GET <presentation's CREDENTIALS_HTTP_URL>       Authorization: Bearer <secret>
200 → {"accounts":[{"instagramAccountId":"…","adminToken":"…","readToken":"…"}]}

GET <ingestion's CREDENTIALS_HTTP_URL>?accountId=…   Authorization: Bearer <secret>
200 → {"instagramAccountId":"…","accessToken":"…","expiresAt":"<ISO-8601>"}
```

`expiresAt` must be more than 24 hours in the future; Knurl rejects a token that is not, rather than using it and failing mid-sync. Both responses are capped at 16 KiB and should be served `Cache-Control: no-store`.

Authentication is required even on a private network, and plaintext HTTP is disabled by default because the bearer credential crosses that connection — enable `CREDENTIALS_HTTP_ALLOW_PLAINTEXT` only for an isolated internal network such as Railway private networking. If an account is later switched from `HTTP` back to `LOCAL`, its previous `auth_config` row becomes active again; delete that stale row before startup when the configured seed token must take effect immediately.

Which posts get synced is admin-curated via an API, not an environment variable or config row — see "Curating what appears in a gallery" below.

### Account-agnostic ingestion

`ingestion-service` discovers and processes *any* account registered in `instagram_accounts`, not just the one it bootstraps at startup. There is no in-application semaphore or coordinator - work is claimed straight out of Postgres using `FOR UPDATE SKIP LOCKED`, so any number of worker coroutines, in this process or several others sharing the same database, can safely pull work with no double-processing and no separate coordination service.

Two independent kinds of work are claimed this way:

- **A due account's feed sync.** `instagram_accounts.last_synced_at`/`sync_claimed_at` track this: an account is due once `last_synced_at` is null or older than `INGESTION_INTERVAL_SECONDS` (in `RUN_ONCE` mode, always - see below), and claimable once `sync_claimed_at` is null or older than `ACCOUNT_SYNC_LEASE_SECONDS`. A feed-sync worker claims one due account at a time (in one fast statement - the claim doesn't hold a transaction across the sync itself), pages its Graph API feed, upserts catalog metadata, enqueues newly-eligible items for download, then clears the claim and stamps `last_synced_at` on success. Under `RUN_ONCE`, "due" ignores `INGESTION_INTERVAL_SECONDS` and uses a cutoff fixed at the moment the process started instead: every account is due at least once per invocation, but a successfully-synced account can't be reclaimed a second time *within that same invocation* (its `last_synced_at` moves past the fixed cutoff the instant it succeeds) - the operator's own trigger (a schedule, or a manual re-run right after curating a gallery) is the real cadence control for a one-shot process, not a persisted timestamp from a previous invocation.
- **A pending download.** Instead of downloading inline during a feed sync, an eligible catalog item is enqueued into `pending_downloads` (identity only - `instagram_account_id`/`instagram_media_id`, no payload, since Instagram's CDN URLs are short-lived and never persisted). A download worker claims a batch (`DOWNLOAD_CLAIM_BATCH_SIZE`) of `UNCLAIMED` items - or `CLAIMED` items whose `claimed_at` is older than `DOWNLOAD_CLAIM_LEASE_SECONDS` - fetches each item's fresh metadata from the Graph API right before downloading, processes it, and marks it `COMPLETE` on success. `COMPLETE` rows are kept for `COMPLETED_DOWNLOAD_RETENTION_HOURS` (for operator visibility) before a periodic sweep purges them.

Both claims are **fast claim-and-release, not a held transaction**: the connection is released immediately after claiming, the actual sync/download work runs with nothing held open, and a second short statement records completion. A worker that crashes or fails mid-item simply leaves its claim in place with a now-stale timestamp - the next claim query treats that identically to a fresh crash and retakes it, with no separate reaper process, no lease-expiry timer to run, and no distinction between "failed cleanly" and "crashed outright". Look for a `WARN`-level "Reclaiming abandoned ..." log line if this happens; it's expected occasionally (a deploy killing a worker mid-item) and only a problem if it happens *often*, which usually means a lease is set too short for how long the work actually takes.

`INSTAGRAM_BUSINESS_ACCOUNT_ID`/`INSTAGRAM_ACCESS_TOKEN` remain a **single-account bootstrap/convenience path only** - `ingestion-service` anchors and seeds exactly that one account at startup, and this does not limit which accounts its workers can subsequently claim and sync. Note that anchoring writes no credentials: the account row carries identity and sync-claim state, nothing more.

A multi-account deployment runs in `HTTP` credential mode, where the control plane is the provisioning path — `presentation-service` anchors an `instagram_accounts` row for every account its credential fetch returns, and ingestion's workers then claim it like any other. In `LOCAL` mode there is no multi-account path, by design: see [Credential modes](#credential-modes).

### Retention and deletion

A gallery shows exactly the items in it. **Removing an item takes it out of that gallery immediately**, but its downloaded media is only on a clock once it has left *every* gallery — an item in two galleries and removed from one is completely unaffected. Once it leaves the last one, the media is kept in the bucket for a grace period (default 30 days) so the decision stays reversible: add it back to any gallery inside that window and it reappears with no re-download, because the files never left. Once the grace period elapses, the post row and its S3 objects are deleted together on the next ingestion cycle. Adding it back clears the clock entirely, so a later removal starts a fresh countdown rather than resuming.

To skip the grace period, use `DELETE /api/v1/admin/accounts/{accountId}/catalog/media` (below), which also removes the item from every gallery holding it. The catalog entry and its browse thumbnail survive either way, so a deleted item stays listed in the admin catalog and can be added to a gallery again later to download it afresh.

A separate **orphan sweep** runs about once a day and deletes any object in the bucket that the database has no record of — files left behind by an upload that failed partway through a carousel, for instance. This is what upholds the invariant that the database knows about every object in the bucket. Because object keys carry no account segment, the sweep necessarily spans every account in the database, which means **one bucket per database**: pointing two deployments with separate databases at a shared bucket would have each sweep delete the other's files.

**A post that disappears from Instagram itself is removed right away**, with none of the above waiting. Every sync cycle, `ingestion-service` compares what Instagram's feed actually returned against what's already in the catalog; anything missing — deleted on Instagram, or the account it belonged to made private — is deleted from the catalog immediately, and if it had been downloaded, from every gallery holding it and from the bucket too. There's no grace period and re-adding doesn't help, because the media genuinely isn't there to re-fetch — that's also why this ignores `is_pinned`. The one safeguard is a sanity check: if an implausibly large fraction of the catalog vanishes in a single cycle (more than `vanished_media_max_percent`, above a small floor that always lets a handful of items through), nothing is deleted and an error is logged instead, since that's more likely a broken sync than a genuine mass deletion.

All of it is tuned by **global** (not per-account) runtime-configurable rows in the `sync_configurations` table, applied independently within each account's own post pool. Change them with plain SQL; no redeploy needed.

| Key | Default | Meaning |
| --- | --- | --- |
| `retention_days` | `30` | Days between an item leaving its last gallery and its media being deleted. |
| `orphan_sweep_interval_hours` | `24` | Minimum gap between full bucket sweeps. |
| `orphan_grace_minutes` | `60` | An object younger than this is never treated as an orphan, so an upload still in progress is never deleted. |
| `orphan_sweep_max_deletes` | `1000` | Cap on objects deleted per sweep. Hitting it is logged loudly. |
| `orphan_sweep_dry_run` | `false` | Set to `true` to have the sweep log what it *would* delete and delete nothing. Worth doing once on a new deployment. |
| `last_orphan_sweep_at` | — | Written by the sweep itself (epoch seconds), not an operator knob. Deleting this row forces the next cycle to sweep. |
| `vanished_media_max_percent` | `50` | Above a small floor of items, refuse to remove more than this percent of an account's catalog in one cycle for media Instagram's feed no longer returned — logged as an error rather than silently trusted. |

**`is_pinned` has no API or UI.** It exempts a post from time-based retention — but *not* from an explicit `DELETE`, since that is an active instruction rather than a passive safety net. It is never written by any code path in this repository; the only way to pin a post today is a manual `UPDATE instagram_posts SET is_pinned = TRUE WHERE id = '…'` against the database.

## Local development with Docker Compose

`docker-compose.yml` at the repo root brings up just the two dependencies both services need - Postgres 18 and a MinIO container standing in for the S3 bucket. It does **not** run `ingestion-service`/`presentation-service` themselves; run those with Gradle against this stack so you get fast iteration (rebuilds, debugger, etc.).

```bash
docker compose up -d      # starts postgres + minio, then creates the knurl-media bucket
docker compose ps         # confirm both are (healthy)
docker compose down -v    # stop and wipe all data when you want a clean slate
```

MinIO's web console is at http://localhost:9001 (login `knurl` / `knurl-dev-secret`) if you want to browse uploaded objects.

> **Schema changes are folded into `V1__init_instagram_aggregator.sql` rather than stacked as new migrations**, because there is no production data to preserve yet. Flyway validates checksums, so a database that already ran an older `V1` will **fail to start** after one of these edits. Reset it (`docker compose down -v`, or drop the schema on a deployed database) — the data is re-derivable: `ingestion-service` re-catalogs the whole feed on its next cycle. Note that galleries and their contents are *not* re-derivable and will need re-creating. Once real data exists that matters, switch to stacking `V2`, `V3`, … instead.
>
> The credential-mode work dropped `instagram_accounts.admin_token_hash`/`read_token_hash` under this policy, so **every existing database must be reset before it will start again**, and any galleries on it re-created by hand.

Each service loads its own `config/application-local.conf` (shared dev config, tracked in git) on startup — `ingestion-service/config/application-local.conf` and `presentation-service/config/application-local.conf`. These already match the credentials above, supply `ingestion-service`'s dev `CREDENTIAL_ENCRYPTION_KEY`, and switch `uiEnabled` back to `true` (its default is `false` everywhere else - see the `UI_ENABLED` row above). They also supply `presentation-service`'s dev `LOCAL`-mode account id and token pair, so the bundled UI works out of the box.

These files are read from disk relative to each service's project directory, so `./gradlew :<service>:run` picks them up automatically while the built jar and image never see them — a deployment gets `application.conf` and real environment variables only. For `ingestion-service`, you'll also need real Instagram credentials:

**Create `ingestion-service/config/application-instagram.conf`** (gitignored):
```hocon
# ingestion-service/config/application-instagram.conf (local only, do NOT commit)
instagram {
  accessToken = "your-long-lived-access-token"
  businessAccountId = "your-business-account-id"
}
```

Set `businessAccountId` to the same value as `presentation-service`'s `local.instagramBusinessAccountId` (`dev-account` in the checked-in dev config) so both services are talking about one account — or override the latter with `INSTAGRAM_BUSINESS_ACCOUNT_ID`.

Then run either service:

```bash
./gradlew :presentation-service:run
# in another shell:
RUN_ONCE=true ./gradlew :ingestion-service:run   # drains every currently due account/pending download once, then exits
```

`ingestion-service` applies the Flyway migration automatically on startup - there's nothing to run by hand against this stack.

## Running database migrations

Migrations live in `shared-domain/src/main/resources/db/migration` and run automatically whenever `ingestion-service` starts (`presentation-service` never touches the schema — see `AGENTS.md`). There's no separate migration command - see the Docker Compose section above for the quickest way to get a local Postgres to migrate against.

## Local build & test

```bash
./gradlew build          # compile + Kotlinter lint + Kotest across all modules
./gradlew lintKotlin      # lint only
./gradlew formatKotlin    # auto-fix formatting
./gradlew :presentation-service:run
./gradlew :ingestion-service:run
```

## Galleries

An account has as many named galleries as it likes, and **no default one** — a gallery must be created before anything can be served. The point is reuse: the same photo can sit in a "Travel" gallery and a "Portfolio" gallery at once, curated independently, and it is only downloaded once.

A gallery has two identities, deliberately:

- **`id`** — an immutable uuid, assigned by the database. This is what public URLs are built from, so renaming never breaks a link.
- **`name`** — a label an admin can change at any time. Unique per account and compared case- and whitespace-insensitively, so `Travel`, `travel` and `  Travel  ` are the same name and the second one is a `409`.

Each gallery keeps its **own** view/click counters for every item, so `sort=views` reflects that gallery's audience rather than a number pooled across all of them. The flip side is that removing an item from a gallery discards that gallery's counters for it; re-adding starts from zero.

> **Gallery names require that account's read token.** `GET /api/v1/accounts/{accountId}/galleries` returns every gallery's name and item counts only to callers that provide that account's `READ_TOKEN`. This token is intentionally less privileged than an account's admin token, but browser clients can still expose it — and unlike a single deployment-wide token, a leaked one only ever exposes the one account it belongs to.

## Curating what appears in a gallery

The Graph API has no endpoint to resolve an arbitrary shortcode to a media id directly — it only lists the account's own media. So `ingestion-service` catalogs the account's *entire* media feed into `instagram_media_catalog` every sync cycle (metadata only — no download, no S3 write), and an admin picks which of those go into which gallery:

1. `POST /api/v1/admin/accounts/{accountId}/galleries` — create a gallery, if you don't have one yet.
2. `GET /api/v1/admin/accounts/{accountId}/catalog` — browse candidates by shortcode, caption, and date, 50 per page. Narrow the search server-side with `?galleryId=…`, `?inAnyGallery=false`, `?mediaType=VIDEO`, or `?includeNotDigestible=false` before paging through what's left. Every item reports the `galleryIds` it already belongs to.
3. `PATCH /api/v1/admin/accounts/{accountId}/galleries/{galleryId}/items` with the shortcodes to add or remove (see the API reference below).
4. On the account's next feed sync, `ingestion-service` enqueues anything newly added that it doesn't already have for a download worker to fetch and store shortly after (see [Account-agnostic ingestion](#account-agnostic-ingestion)). An item already downloaded for another gallery is reused as-is.

Adding or removing takes effect immediately — the gallery changes on the next request, without waiting for a sync cycle. A removed item is reversible for the length of the retention window, and only once it has left *every* gallery. To remove something and its files without waiting that out, `DELETE .../catalog/media`. Either way the catalog entry survives, so nothing is ever permanently lost from the browse list. See [Retention and deletion](#retention-and-deletion).

Between step 3 and step 4 a newly added item is a member of the gallery but has nothing to show yet. That gap is why the gallery list reports both `itemCount` and `publishedCount` — otherwise a freshly curated item just looks missing.

`{accountId}` is the Graph API business account id (`INSTAGRAM_BUSINESS_ACCOUNT_ID` / `businessAccountId`) — public, non-secret, and safe to put in a URL. Every admin endpoint requires `Authorization: Bearer <that account's ADMIN_TOKEN>`, and every gallery-read endpoint requires `Authorization: Bearer <that account's READ_TOKEN>`; a token of either kind valid for one account can never act on another account's catalog, galleries, or gallery reads, and a shortcode belonging to another account reports as `notFound` rather than crossing the boundary. `404` for an unregistered account, `401` for the right kind of token but the wrong value.

## API

Every route below is also rate-limited (`RATE_LIMIT_PER_MINUTE`, default 120/minute per client IP).

- `GET /api/v1/accounts/{accountId}/galleries?limit=50&page=1` — requires `Authorization: Bearer <that account's READ_TOKEN>`, paginated. Lists the account's galleries ordered by name, each with `id`, `name`, `itemCount`, `publishedCount`, `createdAt` and `updatedAt`. This is how a permitted gallery client discovers what galleries exist without holding an admin token.
- `GET /api/v1/accounts/{accountId}/galleries/{galleryId}?sort=recent|views&limit=12&page=1` — requires `Authorization: Bearer <that account's READ_TOKEN>`, paginated (see [Paginated responses](#paginated-responses)). Returns the gallery's own summary under `gallery` alongside `data`, so a client following a link knows what it got. A malformed `{galleryId}` is a `400`; one that is not this account's is a `404`. `limit` defaults to `12` and is silently clamped to `1..50`; `page` defaults to `1`. `sort` must be `recent` or `views` (anything else returns `400`). Each post in `data` carries a `mediaItems` array (one entry per downloaded image/video, in carousel display order — a single-image/video post still has exactly one entry) of presigned S3 GET URLs (valid for `S3_PRESIGNED_GET_TTL_SECONDS`, default 6h). Every URL has persisted metadata for UI layout: `smallUrl`/`smallFileSizeBytes`/`smallWidth`/`smallHeight`, `largeUrl`/`largeFileSizeBytes`/`largeWidth`/`largeHeight`, and optional `videoUrl`/`videoFileSizeBytes`/`videoWidth`/`videoHeight`; `mediaUrlExpiresAt` says when to refetch. Each entry also has `mediaType` (`IMAGE`/`VIDEO` — this item's own type, independent of the post's own `mediaType`, so a UI can attach the right controls to each carousel slide). `CAROUSEL_ALBUM` posts can have multiple entries; other `mediaType`s always have exactly one.
  `viewCount`/`clickCount` on each post are **that gallery's** counters, not the post's across all galleries.
- `POST /api/v1/accounts/{accountId}/galleries/{galleryId}/track` — requires `Authorization: Bearer <that account's READ_TOKEN>`. Body: `{ "id": "<post uuid>", "event": "view" | "click" }`. Increments that gallery's counters only; a post that is not in this gallery returns `404`.
- `POST /api/v1/admin/accounts/{accountId}/galleries` — admin auth. Body: `{ "name": "Travel" }` → `201` with the new gallery. `409` if the account already has that name (case- and whitespace-insensitively); `400` if the name is empty or over 100 characters after trimming.
- `PATCH /api/v1/admin/accounts/{accountId}/galleries/{galleryId}` — admin auth. Body: `{ "name": "Travel 2024" }` → `200`. **The id and the gallery's contents are unchanged**, so existing links keep working. `409` on a name collision, `404` if it is not this account's.
- `DELETE /api/v1/admin/accounts/{accountId}/galleries/{galleryId}` — admin auth. A gallery still holding items returns `409` unless you repeat the call with `?force=true`; that guard exists because deleting a gallery silently starts a retention countdown on every item that was only in it. The success response reports `itemsRemoved` and `itemsReleased` (how many just started that countdown).
- `PATCH /api/v1/admin/accounts/{accountId}/galleries/{galleryId}/items` — admin auth. Body: `{ "add": ["Cabc123XYZ"], "remove": ["Cdef456UVW"] }` — at most 100 shortcodes per request. Returns `{ "added": [...], "alreadyPresent": [...], "removed": [...], "notFound": [...] }`; `alreadyPresent` is separated from `added` so a client can tell "it was already there" from a typo'd shortcode. A shortcode in both lists is a `400`, as is an empty request.
- `GET /api/v1/admin/accounts/{accountId}/catalog` — requires `Authorization: Bearer <that account's ADMIN_TOKEN>`. Paginated in the same envelope as the gallery, with `limit` defaulting to `50` (clamped to `1..50`) and `page` to `1`. Filters apply server-side, across the whole catalog rather than one page:
  - `galleryId=<uuid>` — only items in that gallery.
  - `inAnyGallery=true|false` — only items that are in at least one gallery, or in none.
  - `mediaType=IMAGE|VIDEO|CAROUSEL_ALBUM` — repeatable (`?mediaType=IMAGE&mediaType=VIDEO`); omit for all types.
  - `includeNotDigestible=true|false` — defaults to `true`; `false` hides items ingestion has flagged as never downloadable.

  Any unrecognised filter value returns `400`. `404` for an unregistered account, `401` for the wrong token.
  Each item carries `galleryIds` (every gallery it belongs to) plus `deselectedAt` and `purgeRequestedAt` — non-null means its downloaded media is on a deletion clock (see [Retention and deletion](#retention-and-deletion)).
- `DELETE /api/v1/admin/accounts/{accountId}/catalog/media` — same auth. Deletes the downloaded media of the named items outright, skipping the retention grace period. Body: `{ "shortcodes": ["Cabc123XYZ", "Cdef456UVW"] }` — at most 100 per request; empty or over-cap returns `400`.

  Returns **`202 Accepted`**, not `200`, with a status for every requested shortcode:

  ```json
  {
    "requestedCount": 3,
    "acceptedCount": 1,
    "results": [
      { "shortcode": "Cabc123XYZ", "status": "ACCEPTED" },
      { "shortcode": "Cdef456UVW", "status": "NOT_DOWNLOADED" },
      { "shortcode": "Cghi789RST", "status": "NOT_FOUND" }
    ]
  }
  ```

  `ACCEPTED` means the item had downloaded media; `NOT_DOWNLOADED` that it exists in the catalog but had nothing downloaded to delete; `NOT_FOUND` that this account has no such shortcode. Either way it is removed from every gallery holding it. `results` follows the order you asked in, so it can be zipped positionally with your request.

  The response is `202`, and the field is `acceptedCount` rather than `deletedCount`, because the deletion is not finished when you get it: the items leave every gallery immediately, but `presentation-service` never touches the bucket — `ingestion-service` deletes the files on the account's next feed sync (a persistent daemon picks this up within `INGESTION_INTERVAL_SECONDS`, default 15 minutes; a `RUN_ONCE`/cron-triggered deployment picks it up on its very next invocation, whenever that's scheduled). The catalog entry and its browse thumbnail survive, so the item stays listed and can be added to a gallery again later to download it afresh.
- `GET /openapi.json` — the generated OpenAPI 3 contract for the above.

### Paginated responses

All list endpoints (galleries, gallery content, and catalog) return the same envelope. A page holds at most **50 records** on either endpoint:

```json
{
  "data": [ { "id": "...", "mediaType": "IMAGE" } ],
  "pagination": {
    "totalRecords": 493,
    "currentPage": 2,
    "totalPages": 10,
    "links": {
      "first": "/api/v1/accounts/1784.../galleries/019...?sort=recent&limit=50&page=1",
      "prev":  "/api/v1/accounts/1784.../galleries/019...?sort=recent&limit=50&page=1",
      "self":  "/api/v1/accounts/1784.../galleries/019...?sort=recent&limit=50&page=2",
      "next":  "/api/v1/accounts/1784.../galleries/019...?sort=recent&limit=50&page=3",
      "last":  "/api/v1/accounts/1784.../galleries/019...?sort=recent&limit=50&page=10"
    }
  }
}
```

- `totalRecords` is the total matching the query, not the size of `data` — apply a filter and it shrinks accordingly.
- `prev` and `next` are `null` on the first and last page. `first`, `self` and `last` are always present; an empty result set still reports a coherent page 1 of 1.
- Links are **relative** (path + query) and preserve every query parameter you sent, rewriting only `page` — so following `next` keeps your sort and filters. Prefer following them to assembling URLs yourself.
- Both `limit` and `page` are silently clamped rather than rejected: `limit` into `1..50`, and `page` into `1..totalPages`. Asking for page 999 of 10 returns page 10, and `pagination.currentPage` tells you which page you actually got.
- `GET /docs` — a Swagger UI browsing that contract interactively.
