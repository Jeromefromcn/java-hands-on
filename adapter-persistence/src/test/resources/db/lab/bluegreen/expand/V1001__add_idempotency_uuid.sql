-- Test-only. Expand, step 1: the new column arrives nullable, beside the old one. Nothing the old
-- version writes is affected.
ALTER TABLE loan_trade ADD COLUMN idempotency_uuid uuid;
CREATE UNIQUE INDEX loan_trade_idempotency_uuid_key ON loan_trade (idempotency_uuid);
