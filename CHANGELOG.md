# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

### Changed

- **Breaking:** combine HTTP presentation and ingestion into `knurl-app`, with one `castab/knurl` image and a fresh PostgreSQL schema baseline requiring an explicit reset.
- Manage multiple accounts through a deployment-authorized API. Store Instagram/admin/S3 secret credentials encrypted in PostgreSQL, with public read tokens and account IDs.
- Give each account its own S3-compatible bucket configuration and fixed account prefix; isolate presigning, uploads, retention, and orphan sweeping even in shared buckets.
- Run durable ingestion jobs through account-authorized API calls or per-account schedules, with coalescing, bounded retries, restart recovery, progress, and 30-day history.
- Add bundled UI controls for ingestion and scheduling; replace the local MinIO stack with community Garage.
- Key rate limits on the edge-set client address header (`CLIENT_IP_HEADER`, default Cloudflare's `CF-Connecting-IP`) instead of the caller-supplied first `X-Forwarded-For` hop; cap every request body at 64 KB; require read/admin tokens of at least 16 characters.
- Add an unauthenticated `GET /health` database probe and an image `HEALTHCHECK`.
- Replace external credential-broker modes, global bucket credentials, separate service entrypoints, and two-image publishing. Document the new operational and security boundaries.


## [0.1.3] - 2026-09-23

### Fixed

- The bundled admin UI's catalog browser no longer keeps showing an item as checked/selected after it becomes non-digestible and is reactively removed from its gallery (v0.1.2): the checkbox is disabled and unchecked, and the card no longer renders with the "selected" highlight, matching the fact that it's no longer a real gallery member.

## [0.1.2] - 2026-09-22

### Fixed

- A gallery item that becomes non-digestible (e.g. Instagram permanently omits its `media_url` after a copyright flag) is now automatically removed from every gallery holding it and starts its normal retention countdown, instead of silently remaining "selected" content that can never be downloaded.
- The admin gallery-items endpoint now refuses to (re-)add a currently non-digestible item, closing a gap where it could be put back into a gallery immediately after being removed for that reason.

## [0.1.1] - 2026-09-22

### Fixed

- Prevented the curated-rank input's native spinner controls from being clipped in narrow admin catalog cards.

## [0.1.0] - 2026-09-22

### Added

- Curator-defined per-item gallery ranks, editable through the admin API and bundled UI.
- A forward Flyway migration that preserves existing gallery memberships while adding rank support.

### Changed

- Gallery reads now default to curated order: explicit ascending rank, then newest Instagram content date.
- Schema evolution is forward-only; `V1__init_instagram_aggregator.sql` remains immutable.

## [0.0.1] - 2026-09-22

### Added

- Initial public release of the Knurl presentation and ingestion service images.
