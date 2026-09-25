-- Stage 6, V1: the ledger's tables.
--
-- Money is always an amount column and a currency column, side by side, never one without the
-- other. numeric(22,6): the widest currency scale is 3 (BHD), so 6 leaves room that is never
-- used, and 22 - 6 = 16 integer digits. The column does not enforce the currency's scale; the
-- mapper refuses a stored amount that has more precision than its currency.
--
-- Rates are unconstrained numeric: Postgres stores the value as given, including its scale, and
-- Rate is deliberately not normalised. A fixed scale would round or pad it on the way through.
--
-- No CHECK constraints on quantities or status: the domain refuses those states, and a row that
-- violates them is refused again when it is read back (LoanPosition.restore).

CREATE TABLE counterparty (
    id                    text          PRIMARY KEY,
    name                  text          NOT NULL,
    credit_limit_amount   numeric(22,6) NOT NULL,
    credit_limit_currency char(3)       NOT NULL
);

CREATE TABLE security (
    isin                  char(12)      PRIMARY KEY,
    name                  text          NOT NULL,
    currency              char(3)       NOT NULL,
    market_price_amount   numeric(22,6) NOT NULL,
    market_price_currency char(3)       NOT NULL
);

CREATE TABLE loan_position (
    id                   text          PRIMARY KEY,
    counterparty_id      text          NOT NULL REFERENCES counterparty (id),
    isin                 char(12)      NOT NULL REFERENCES security (isin),
    original_quantity    bigint        NOT NULL,
    outstanding_quantity bigint        NOT NULL,
    status               text          NOT NULL,
    collateral_amount    numeric(22,6) NOT NULL,
    collateral_currency  char(3)       NOT NULL,
    rule_type            text          NOT NULL,
    flat_rate            numeric       NOT NULL
);

-- idempotency_key is unique across both directions: a key identifies a submission, and a return
-- that reuses an open's key is the same submission sent twice, not a new one.
CREATE TABLE loan_trade (
    id                  bigserial     PRIMARY KEY,
    idempotency_key     text          NOT NULL UNIQUE,
    position_id         text          NOT NULL REFERENCES loan_position (id),
    counterparty_id     text          NOT NULL REFERENCES counterparty (id),
    isin                char(12)      NOT NULL REFERENCES security (isin),
    direction           text          NOT NULL,
    quantity            bigint        NOT NULL,
    unit_price_amount   numeric(22,6) NOT NULL,
    unit_price_currency char(3)       NOT NULL,
    trade_date          date          NOT NULL,
    settlement_date     date          NOT NULL
);

-- One entry per position per day. The primary key is that rule, and its index leads with
-- position_id; stage 6's EXPLAIN exercise is about what that order does to a date-range query.
CREATE TABLE accrual_entry (
    position_id   text          NOT NULL REFERENCES loan_position (id),
    business_date date          NOT NULL,
    fee_amount    numeric(22,6) NOT NULL,
    fee_currency  char(3)       NOT NULL,
    PRIMARY KEY (position_id, business_date)
);
