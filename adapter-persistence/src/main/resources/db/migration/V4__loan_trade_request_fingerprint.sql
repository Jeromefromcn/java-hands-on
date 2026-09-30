-- Stage 7. The SHA-256 of the request that booked each trade, so a later request with the same
-- idempotency key can be replayed (same fingerprint) or refused (different). Nullable: trades
-- booked before this migration have none, and a replay against one is refused rather than guessed.
ALTER TABLE loan_trade ADD COLUMN request_fingerprint char(64);
