-- Stage 11. Support for the batch jobs.
--
-- batch_skip: a row per item a batch skipped, so a skip is a record and not only a counter on a
-- step. One row per (job, scope, item): a restart that skips the same item again replaces the
-- reason instead of adding a second row.
CREATE TABLE batch_skip (
    job         text        NOT NULL,
    scope       text        NOT NULL,
    item_key    text        NOT NULL,
    phase       text        NOT NULL,
    reason      text        NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (job, scope, item_key)
);

-- The accrual reader asks "does this position have a borrow trade that settled by D" once per
-- position; without an index that is a scan of loan_trade per row. (Found by measuring a 20,000
-- position ledger: docs/evidence/11-chunk-size.txt.)
CREATE INDEX loan_trade_position_direction ON loan_trade (position_id, direction, settlement_date);

-- The counterparty partition scheme reads one counterparty's positions in id order.
CREATE INDEX loan_position_counterparty_id ON loan_position (counterparty_id, id);
