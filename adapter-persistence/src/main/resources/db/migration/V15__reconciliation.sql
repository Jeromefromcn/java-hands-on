-- Stage 11. Reconciliation of the ledger against a custodian's snapshot.
--
-- custodian_holding: the snapshot as loaded. Money is an amount and a currency, side by side; a
-- line whose amount has more decimals than its currency, or whose currency is missing or unknown,
-- never gets in (it is refused when the file is read and recorded in batch_skip).
CREATE TABLE custodian_holding (
    snapshot_id    text          NOT NULL,
    position_id    text          NOT NULL,
    quantity       bigint        NOT NULL,
    value_amount   numeric(22,6) NOT NULL,
    value_currency char(3)       NOT NULL,
    PRIMARY KEY (snapshot_id, position_id)
);

-- recon_break: one row per (snapshot, position), the first break that applies. The primary key is
-- what makes running the same snapshot again add nothing. disposition says what is to be done:
-- rows in MANUAL and ESCALATED that are not resolved are the queue a person works.
CREATE TABLE recon_break (
    snapshot_id         text          NOT NULL,
    position_id         text          NOT NULL,
    kind                text          NOT NULL,
    disposition         text          NOT NULL,
    business_date       date          NOT NULL,
    our_quantity        bigint,
    their_quantity      bigint,
    our_value_amount    numeric(22,6),
    our_value_currency  char(3),
    their_value_amount  numeric(22,6),
    their_value_currency char(3),
    detail              text          NOT NULL,
    attempts            int           NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    resolved_at         timestamptz,
    PRIMARY KEY (snapshot_id, position_id)
);

CREATE INDEX recon_break_retry ON recon_break (next_attempt_at) WHERE disposition = 'RETRY_SETTLEMENT';
CREATE INDEX recon_break_open ON recon_break (disposition) WHERE resolved_at IS NULL;
