# Knurl

Knurl is a low-memory Instagram media aggregation engine written in Kotlin. It's a monorepo of two long-running JVM services that share a Postgres database and an S3-compatible object bucket, with multi-account support built in: several `ingestion-service` instances (one per Instagram account) can share one database, and a single `presentation-service` deployment serves/manages every one of those accounts:

- **`shared-domain`** — Flyway migrations, JDBI repositories, connection pool setup, and the plain data classes both services depend on.
- **`ingestion-service`** — one process per Instagram account; a daemon (or cron-triggered, one-shot process) that catalogs the account's entire media feed, downloads/stores the posts an admin has curated into a gallery, enforces retention, and sweeps orphaned objects out of the bucket.
- **`presentation-service`** — a multi-tenant read API (built on [http4k](https://www.http4k.org)) that serves any registered account's named galleries as JSON with S3 presigned URLs, plus a Bearer-secured analytics tracking endpoint and a per-account-authenticated admin API for curating what gets synced.

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

Each service reads its config from a bundled `application.conf` (HOCON), which can pull these in via environment variables (`${?VAR}`) for deployed environments. Locally, the `application-local.conf` / `application-instagram.conf` overrides described below supply these instead. A variable marked **required** has no default anywhere and the service fails fast at startup if it's missing from every layer.

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
| `API_BEARER_TOKEN` | yes | presentation | Token required (as `Authorization: Bearer <token>`) on `POST /api/v1/accounts/{accountId}/galleries/{galleryId}/track`. |
| `PORT` | no | presentation | HTTP port. Default `8080`. |
| `UI_ENABLED` | no | presentation | Set to `false` to disable the built-in gallery/admin UI served at `/` (the JSON API, `/docs`, and `/openapi.json` are unaffected). Default `true`. **The UI has no login of its own** — it is a thin client that asks the operator to paste tokens, holds them in `sessionStorage`, and sends them as Bearer headers. Set this to `false` on any deployment where the admin surface should not be publicly reachable. |
| `INSTAGRAM_ACCESS_TOKEN` | yes | ingestion | Long-lived Instagram Graph API access token. |
| `INSTAGRAM_BUSINESS_ACCOUNT_ID` | yes | ingestion | The Instagram Business Account ID whose media feed is polled. Public, non-secret - it's also the `{accountId}` path segment on presentation-service. |
| `ADMIN_TOKEN` | yes | ingestion | Operator-chosen secret (not an Instagram credential) gating this account's admin catalog/selection API on presentation-service. Registered into the database on every `ingestion-service` startup - change and restart to rotate it. |
| `INSTAGRAM_API_VERSION` | no | ingestion | Graph API version. Default `v21.0`. |
| `RUN_ONCE` | no | ingestion | `true` to run a single sync cycle and exit (for cron-triggered deployment) instead of looping. Default `false`. |
| `INGESTION_INTERVAL_SECONDS` | no | ingestion | Delay between sync cycles when not running once. Default `900`. |
| `DB_MAX_POOL_SIZE` / `DB_MIN_IDLE` / `DB_IDLE_TIMEOUT_MS` | no | both | HikariCP pool overrides. Defaults (`2` / `1` / `30000`) preserve the low-memory connection footprint — don't raise these without reviewing the heap budget. |

**Storage privacy:** `S3_BUCKET_NAME` must be provisioned as a *private* bucket — no public-read bucket policy, no anonymous `s3:GetObject` grant, and no public-access-block override that would allow unsigned requests to succeed. Neither service ever sets an object ACL on upload (`ingestion-service`'s `PutObjectRequest` calls set only `bucket`/`key`/`contentType`), so object visibility is entirely inherited from the bucket's own default (private) configuration. The only supported read path is a presigned GET minted by `presentation-service`; an unsigned request to the plain object URL must return `403`/access-denied. If your provider's default differs (or you're reusing a bucket that previously had public access enabled), disable public access explicitly in that provider's console/IaC before deploying.

**Token exposure:** `API_BEARER_TOKEN` guards a browser-called endpoint (`POST .../galleries/{galleryId}/track`), so any real browser client must ship it to the browser. Treat it as a rate-limiting speed bump against casual abuse, not as authentication — it is not a secret once a public gallery page uses it. A per-account `ADMIN_TOKEN` is a genuine secret: it is never embedded in a page, and the bundled UI keeps it only in `sessionStorage` for the life of the tab.

Which posts get synced is admin-curated via an API, not an environment variable or config row — see "Curating what appears in a gallery" below.

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

Both services load `application-local.conf` (shared dev config, tracked in git) on startup, which already matches the credentials above. For `ingestion-service`, you'll also need Instagram credentials:

**Create `ingestion-service/config/application-instagram.conf`** (gitignored):
```hocon
# ingestion-service/config/application-instagram.conf (local only, do NOT commit)
instagram {
  accessToken = "your-long-lived-access-token"
  businessAccountId = "your-business-account-id"
  adminToken = "pick-your-own-admin-secret"
}
```

Then run either service:

```bash
./gradlew :presentation-service:run
# in another shell:
RUN_ONCE=true ./gradlew :ingestion-service:run   # one sync cycle, then exit
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

> **Gallery names are public.** `GET /api/v1/accounts/{accountId}/galleries` needs no token, so anyone with the account id can see every gallery's name and item counts. That is what lets a site build its own navigation without embedding an admin token, but don't name a gallery something you would not publish.

## Curating what appears in a gallery

The Graph API has no endpoint to resolve an arbitrary shortcode to a media id directly — it only lists the account's own media. So `ingestion-service` catalogs the account's *entire* media feed into `instagram_media_catalog` every sync cycle (metadata only — no download, no S3 write), and an admin picks which of those go into which gallery:

1. `POST /api/v1/admin/accounts/{accountId}/galleries` — create a gallery, if you don't have one yet.
2. `GET /api/v1/admin/accounts/{accountId}/catalog` — browse candidates by shortcode, caption, and date, 50 per page. Narrow the search server-side with `?galleryId=…`, `?inAnyGallery=false`, `?mediaType=VIDEO`, or `?includeNotDigestible=false` before paging through what's left. Every item reports the `galleryIds` it already belongs to.
3. `PATCH /api/v1/admin/accounts/{accountId}/galleries/{galleryId}/items` with the shortcodes to add or remove (see the API reference below).
4. On the next sync cycle, `ingestion-service` downloads and stores anything newly added that it doesn't already have. An item already downloaded for another gallery is reused as-is.

Adding or removing takes effect immediately — the gallery changes on the next request, without waiting for a sync cycle. A removed item is reversible for the length of the retention window, and only once it has left *every* gallery. To remove something and its files without waiting that out, `DELETE .../catalog/media`. Either way the catalog entry survives, so nothing is ever permanently lost from the browse list. See [Retention and deletion](#retention-and-deletion).

Between step 3 and step 4 a newly added item is a member of the gallery but has nothing to show yet. That gap is why the gallery list reports both `itemCount` and `publishedCount` — otherwise a freshly curated item just looks missing.

`{accountId}` is the Graph API business account id (`INSTAGRAM_BUSINESS_ACCOUNT_ID` / `businessAccountId`) — public, non-secret, and safe to put in a URL. Every admin endpoint requires `Authorization: Bearer <that account's ADMIN_TOKEN>`; a token valid for one account can never act on another account's catalog or galleries, and a shortcode belonging to another account reports as `notFound` rather than crossing the boundary.

## API

- `GET /api/v1/accounts/{accountId}/galleries?limit=50&page=1` — **public**, paginated. Lists the account's galleries ordered by name, each with `id`, `name`, `itemCount`, `publishedCount`, `createdAt` and `updatedAt`. This is how a site discovers what galleries exist without holding an admin token.
- `GET /api/v1/accounts/{accountId}/galleries/{galleryId}?sort=recent|views&limit=12&page=1` — public, paginated (see [Paginated responses](#paginated-responses)). Returns the gallery's own summary under `gallery` alongside `data`, so a client following a link knows what it got. A malformed `{galleryId}` is a `400`; one that is not this account's is a `404`. `limit` defaults to `12` and is silently clamped to `1..50`; `page` defaults to `1`. `sort` must be `recent` or `views` (anything else returns `400`). Each post in `data` carries a `mediaItems` array (one entry per downloaded image/video, in carousel display order — a single-image/video post still has exactly one entry) of presigned S3 GET URLs (valid for `S3_PRESIGNED_GET_TTL_SECONDS`, default 6h). Every URL has persisted metadata for UI layout: `smallUrl`/`smallFileSizeBytes`/`smallWidth`/`smallHeight`, `largeUrl`/`largeFileSizeBytes`/`largeWidth`/`largeHeight`, and optional `videoUrl`/`videoFileSizeBytes`/`videoWidth`/`videoHeight`; `mediaUrlExpiresAt` says when to refetch. Each entry also has `mediaType` (`IMAGE`/`VIDEO` — this item's own type, independent of the post's own `mediaType`, so a UI can attach the right controls to each carousel slide). `CAROUSEL_ALBUM` posts can have multiple entries; other `mediaType`s always have exactly one.
  `viewCount`/`clickCount` on each post are **that gallery's** counters, not the post's across all galleries.
- `POST /api/v1/accounts/{accountId}/galleries/{galleryId}/track` — requires `Authorization: Bearer <API_BEARER_TOKEN>`. Body: `{ "id": "<post uuid>", "event": "view" | "click" }`. Increments that gallery's counters only; a post that is not in this gallery returns `404`.
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

  The response is `202`, and the field is `acceptedCount` rather than `deletedCount`, because the deletion is not finished when you get it: the items leave every gallery immediately, but `presentation-service` never touches the bucket — `ingestion-service` deletes the files on its next cycle (within `INGESTION_INTERVAL_SECONDS`, default 15 minutes). The catalog entry and its browse thumbnail survive, so the item stays listed and can be added to a gallery again later to download it afresh.
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
