# Knurl

Knurl is one Kotlin application for curating galleries from multiple Instagram accounts. It serves an HTTP API and runs ingestion as durable coroutine jobs, triggered by an account admin or a per-account interval schedule.

Each account owns its read/admin credentials, Instagram access token, and S3-compatible storage settings. Different accounts can use different buckets or share a bucket with isolated prefixes. Instagram tokens, admin tokens, and S3 secret access keys are encrypted in PostgreSQL using AES-256-GCM. Account IDs and read tokens are public values stored unencrypted.

## Start locally

Requirements: JDK 25, Docker Compose, and the included Gradle wrapper. PostgreSQL 18+ supplies native `uuidv7()` IDs. The development stack uses the community [Garage object store](https://garagehq.deuxfleurs.fr/documentation/quick-start/).

```sh
docker compose up -d
sh gradlew :knurl-app:run
```

On Windows use `.\gradlew.bat :knurl-app:run`. The application serves the optional UI at [localhost:8080](http://localhost:8080), Swagger at [localhost:8080/docs](http://localhost:8080/docs), and the contract at `/openapi.json`.

Only dependencies run in Compose. Garage configures a single-node development cluster and the private `knurl-media` bucket automatically; it keeps its data in the `garage-data` volume. Its S3 endpoint is `http://localhost:3900`, region `garage`, using path-style addressing. There is no MinIO console or separate bucket-init container.

Development defaults live in `knurl-app/config/application-local.conf`, outside the packaged resources. They supply the local database URL, a published development encryption key, a development management token, and enable the UI. They contain no Instagram credentials and register no accounts automatically.

Register an account through the management API. Save this example as a local, gitignored JSON file, replacing the Instagram values and expiry with your own long-lived token:

```json
{
  "instagramAccountId": "17841400000000000",
  "readToken": "example-public-read-token",
  "adminToken": "replace-with-account-admin-token",
  "instagramAccessToken": "replace-with-long-lived-instagram-token",
  "instagramTokenExpiresAt": "2030-01-01T00:00:00Z",
  "storage": {
    "bucketName": "knurl-media",
    "region": "garage",
    "endpoint": "http://localhost:3900",
    "accessKeyId": "GK00000000000000000000000000000000",
    "secretAccessKey": "1111111111111111111111111111111111111111111111111111111111111111",
    "pathStyleAccess": true
  }
}
```

```sh
curl -X POST http://localhost:8080/api/v1/management/accounts \
  -H 'Authorization: Bearer knurl-local-management-token' \
  -H 'Content-Type: application/json' --data-binary @account.local.json
```

Registration leaves scheduling off and does not contact Instagram. Use the account's admin token to request the first job:

```sh
curl -X POST http://localhost:8080/api/v1/admin/accounts/17841400000000000/ingestion/jobs \
  -H 'Authorization: Bearer replace-with-account-admin-token'
```

The response is `202`, with a job ID and a `Location` status URL. After the catalog loads, create a gallery, add its media, then request another job to download the selected content. The UI's admin tab offers Sync now, job progress/results, and scheduling controls. Account registration, credentials, and storage configuration remain API-only.

## Configuration

Only `application.conf` ships in the jar/image. Real environment values take priority over the optional development file. A deployed application can start with an empty database and has no single-account environment seed or global bucket configuration.

| Variable | Meaning | Default |
| --- | --- | --- |
| `DATABASE_URL` | PostgreSQL URL; non-loopback hosts require TLS unless a mode is explicit | Required |
| `CREDENTIAL_ENCRYPTION_KEY` | Base64 encoding of 32 random bytes, kept outside PostgreSQL | Required |
| `MANAGEMENT_TOKEN` | Separate deployment management bearer credential, at least 16 characters | Exactly one management source |
| `MANAGEMENT_TOKEN_FILE` | Mounted management-token file, re-read per request; at most 4096 bytes | Exactly one management source |
| `PORT` | HTTP port | `8080` |
| `UI_ENABLED` | Serve bundled gallery/admin UI | `false` |
| `RATE_LIMIT_PER_MINUTE` | Fixed-window limit per client IP | `120` |
| `CLIENT_IP_HEADER` | Header your edge sets to the connecting client's address; empty to use the socket peer address | `CF-Connecting-IP` |
| `S3_PRESIGNED_GET_TTL_SECONDS` | Lifetime of direct media GET URLs, at most seven days | `21600` |
| `INSTAGRAM_API_VERSION` | Graph API version | `v21.0` |

Old `CREDENTIALS_MODE`, broker settings, `RUN_ONCE`, account token env vars, and global `S3_*` credentials are removed. Use the management API and schedule endpoints instead. Deployment target: one active application instance, a 512 MB JVM heap, Hikari maximum pool size 2/minimum idle 1, one account job at a time, and two shared media-processing permits.

## APIs

All account IDs are public Instagram business account IDs. Gallery reads and tracking require that account's read bearer token; gallery/catalog administration and ingestion require its admin bearer token. Unknown or disabled accounts return `404`; incorrect tokens return `401`. A management token only authorizes management routes and does not substitute for account tokens.

| Endpoint | Purpose |
| --- | --- |
| `GET/POST /api/v1/management/accounts` | List or register accounts, management auth |
| `GET/PATCH /api/v1/management/accounts/{accountId}` | Inspect, replace credentials/storage, enable/disable, management auth |
| `GET /api/v1/accounts/{accountId}/galleries` | List galleries, read auth |
| `GET /api/v1/accounts/{accountId}/galleries/{galleryId}` | Gallery content, read auth |
| `GET /api/v1/accounts/{accountId}/galleries/by-name/{name}` | Resolve a gallery by name, read auth |
| `POST /api/v1/accounts/{accountId}/galleries/{galleryId}/track` | Record gallery views/clicks, read auth |
| `POST /api/v1/admin/accounts/{accountId}/galleries` | Create a gallery |
| `PATCH/DELETE /api/v1/admin/accounts/{accountId}/galleries/{galleryId}` | Rename/delete a gallery |
| `PATCH /api/v1/admin/accounts/{accountId}/galleries/{galleryId}/items` | Add/remove/rank gallery members |
| `GET /api/v1/admin/accounts/{accountId}/catalog` | Browse catalog with server-side filters |
| `DELETE /api/v1/admin/accounts/{accountId}/catalog/media` | Record media-purge intent; returns `202` |
| `GET/PUT /api/v1/admin/accounts/{accountId}/ingestion/schedule` | Read/configure interval schedule |
| `POST/GET /api/v1/admin/accounts/{accountId}/ingestion/jobs` | Queue a job or list history |
| `GET /api/v1/admin/accounts/{accountId}/ingestion/jobs/{jobId}` | Job progress and paginated work results |

Lists use `{ data, pagination: { totalRecords, currentPage, totalPages, links } }`, with page sizes clamped to 1..50. Links are relative and preserve filters. Job details add a `job` summary beside the same paginated item envelope. The checked-in [OpenAPI contract](knurl-app/openapi.json) specifies request fields and responses.

Gallery memberships control downloads and visibility. Removing an item from its last gallery starts the default 30-day retention clock; re-adding it before deletion reuses its existing media. Explicit purge bypasses retention but still runs through ingestion. See [architecture](docs/architecture.md) for source deletion and orphan safeguards.

## Build and verification

```sh
sh gradlew build --no-daemon
sh gradlew :knurl-app:generateOpenApiSpec
sh gradlew formatKotlin
```

Unit tests do not need live services. [Operations](docs/operations.md#integration-tests) documents gated PostgreSQL/Garage tests and disposable test resources. [Deployment and recovery](docs/operations.md), [architecture](docs/architecture.md), and [releasing](docs/releasing.md) cover production operation and the breaking reset required for this iteration.
