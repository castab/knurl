# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

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
