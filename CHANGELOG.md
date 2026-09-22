# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

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
