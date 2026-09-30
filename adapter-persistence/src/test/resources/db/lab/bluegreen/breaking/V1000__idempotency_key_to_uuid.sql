-- Test-only. The one-step change a blue/green deploy cannot survive: the column changes type under
-- the version still serving traffic. See docs/decisions/06-persistence.md.
ALTER TABLE loan_trade ALTER COLUMN idempotency_key TYPE uuid USING idempotency_key::uuid;
