-- Stage 10. The version a price feed assigned to the price now stored. An update is applied only if
-- its version is higher, so a duplicate and a late arrival both change nothing. 0 is "no update has
-- been applied yet"; the feed's versions start at 1.
ALTER TABLE security ADD COLUMN price_version bigint NOT NULL DEFAULT 0;
