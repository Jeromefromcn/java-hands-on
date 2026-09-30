-- Stage 10. The saga that opens a position: credit set aside for an open that is not yet a
-- position, and a log of what the saga has done.
--
-- credit_reservation is keyed by the saga id and follows the same rule as tcc_transaction: a
-- release that arrives before its reserve inserts a RELEASED row, and the reserve that follows is
-- refused. status: RESERVED counts against the counterparty's limit; CONSUMED does not (the
-- position carries the exposure now); RELEASED does not.
CREATE TABLE credit_reservation (
    saga_id         text          PRIMARY KEY,
    counterparty_id text,
    amount          numeric(22,6),
    currency        char(3),
    status          text          NOT NULL
);

-- Written ahead of each step, outside the step's transaction, so a step that rolls back still
-- leaves its entry. (saga_id, seq) is one saga's history in order; the marker step 'saga' with
-- outcome DONE is the end, and its phase says which way it ended.
CREATE TABLE saga_log (
    saga_id text        NOT NULL,
    seq     int         NOT NULL,
    step    text        NOT NULL,
    phase   text        NOT NULL,
    outcome text        NOT NULL,
    detail  text,
    at      timestamptz NOT NULL,
    PRIMARY KEY (saga_id, seq)
);
