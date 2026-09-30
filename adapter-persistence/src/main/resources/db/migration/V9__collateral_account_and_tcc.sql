-- Stage 10. Collateral a counterparty has posted, and the state of every attempt to move it.
--
-- available and frozen are separate columns so that a hold is visible as money that is neither
-- spendable nor gone. The CHECKs are the last line of defence, not the design: every path that
-- changes these columns is guarded so that it cannot reach them. Stage 6 put no CHECKs on
-- quantities because the domain refuses those states; a balance moved by SQL from several places
-- is not the domain's, and a wrong number here is a customer's money.
CREATE TABLE collateral_account (
    counterparty_id text          NOT NULL REFERENCES counterparty (id),
    currency        char(3)       NOT NULL,
    available       numeric(22,6) NOT NULL,
    frozen          numeric(22,6) NOT NULL DEFAULT 0,
    PRIMARY KEY (counterparty_id, currency),
    CHECK (available >= 0),
    CHECK (frozen >= 0)
);

-- One row per transaction id, and the primary key is the whole trick. It is what tells a Cancel
-- that arrives before its Try (an empty rollback) from one that arrives after, what lets a late Try
-- be refused once its Cancel has been seen (suspension), and what makes a repeated Confirm a no-op.
-- A Cancel with no row inserts one, with no counterparty and no amount: a tombstone.
CREATE TABLE tcc_transaction (
    xid             text          PRIMARY KEY,
    counterparty_id text,
    currency        char(3),
    amount          numeric(22,6),
    status          text          NOT NULL,
    updated_at      timestamptz   NOT NULL
);
