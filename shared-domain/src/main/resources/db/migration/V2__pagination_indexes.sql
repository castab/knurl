-- Composite indexes backing the paginated gallery and catalog list endpoints.
--
-- V1's indexes are single-column, so once a list query is both account-scoped *and* sorted -
-- which every one of these endpoints is - Postgres can use an index for the filter or the sort,
-- but not both, and falls back to sorting the account's whole row set on each page request.
--
-- The trailing tiebreaker column matches the ORDER BY in `InstagramPostRepository.findPage` /
-- `CatalogRepository.findPage` exactly. That tiebreaker is required for correctness, not just
-- speed: `timestamp` and `view_count` are not unique, and offset pagination over a non-total
-- order lets the same row appear on two pages (or on none).
CREATE INDEX idx_posts_account_timestamp ON instagram_posts (instagram_account_id, timestamp DESC, id);
CREATE INDEX idx_posts_account_view_count ON instagram_posts (instagram_account_id, view_count DESC, id);
CREATE INDEX idx_catalog_account_timestamp ON instagram_media_catalog (instagram_account_id, timestamp DESC, instagram_media_id);
