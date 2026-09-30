-- Stage 11. Revaluation of a security whose open positions are too many to revalue in the price
-- update's own transaction.
--
-- revaluation_request: written in the price update's transaction when the security has more open
-- positions than the inline limit; the dispatcher launches the revaluation job for it. One row per
-- (security, price version), so a redelivered update cannot ask twice. attempts is how many times
-- the dispatcher launched it; a request that keeps failing is left for a person after the limit.
CREATE TABLE revaluation_request (
    isin          char(12)    NOT NULL REFERENCES security (isin),
    price_version bigint      NOT NULL,
    status        text        NOT NULL,
    attempts      int         NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL,
    completed_at  timestamptz,
    PRIMARY KEY (isin, price_version)
);

CREATE INDEX revaluation_request_pending ON revaluation_request (created_at) WHERE status = 'PENDING';

-- margin_call_marker: one row per (position, price version) that has raised its margin call. It is
-- inserted in the same transaction as the outbox row, so a chunk that is run again raises each call
-- once. No foreign key to loan_position: a marker outliving a position is harmless, and the ledger's
-- own tests empty loan_position freely.
CREATE TABLE margin_call_marker (
    position_id   text   NOT NULL,
    price_version bigint NOT NULL,
    PRIMARY KEY (position_id, price_version)
);

-- The revaluation reader pages one security's positions by id. Without this index that is a scan of
-- every position per page (the inline path's findByIsin... had the same cost once per update).
CREATE INDEX loan_position_isin_id ON loan_position (isin, id);
