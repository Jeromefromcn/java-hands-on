-- Test-only. Contract: runs only once no deployed version writes the text column. The new column
-- keeps its name; renaming it to idempotency_key would be one more incompatible change for the
-- version that writes it, needing an expand/contract of its own.
DROP TRIGGER loan_trade_fill_idempotency_uuid ON loan_trade;
DROP FUNCTION fill_idempotency_uuid();
DROP FUNCTION idempotency_uuid_of(text);
ALTER TABLE loan_trade DROP COLUMN idempotency_key;
ALTER TABLE loan_trade ALTER COLUMN idempotency_uuid SET NOT NULL;
