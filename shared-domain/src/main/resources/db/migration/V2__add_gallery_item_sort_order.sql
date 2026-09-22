-- Curator-defined placement is per membership: the same media may have a different position in
-- each gallery. NULL keeps the item in the natural fallback order (Instagram content timestamp).
ALTER TABLE gallery_items ADD COLUMN sort_order BIGINT;
