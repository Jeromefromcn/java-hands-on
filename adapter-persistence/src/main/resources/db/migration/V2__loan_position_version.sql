-- Stage 6, V2: optimistic locking arrives the way it would on a live table, as a column added
-- with a default so every existing row starts at version 0. Adding a NOT NULL column with a
-- constant default is a metadata-only change in Postgres 11+: no table rewrite.
ALTER TABLE loan_position ADD COLUMN version bigint NOT NULL DEFAULT 0;
