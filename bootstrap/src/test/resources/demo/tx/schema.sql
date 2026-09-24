-- Stage 5 demo schema. Run on every demo context start (spring.sql.init.mode=always):
-- dropping first means no context sees rows an earlier one left behind.

DROP TABLE IF EXISTS idempotency_key;
DROP TABLE IF EXISTS accrual_entry;
DROP TABLE IF EXISTS accrual_run;
DROP TABLE IF EXISTS loan_trade;
DROP TABLE IF EXISTS loan_position;

-- No CHECK on outstanding_quantity: a negative quantity is rejected by domain, not here.
CREATE TABLE loan_position (
    id                   text   PRIMARY KEY,
    outstanding_quantity bigint NOT NULL,
    version              bigint NOT NULL DEFAULT 0
);

CREATE TABLE loan_trade (
    id          bigserial PRIMARY KEY,
    position_id text      NOT NULL,
    quantity    bigint    NOT NULL
);

CREATE TABLE accrual_run (
    id            bigserial PRIMARY KEY,
    business_date date      NOT NULL
);

-- Money crosses into a column here: amount and currency, never one without the other.
CREATE TABLE accrual_entry (
    position_id   text          NOT NULL,
    business_date date          NOT NULL,
    amount        numeric(19,6) NOT NULL,
    currency      char(3)       NOT NULL,
    PRIMARY KEY (position_id, business_date)
);

CREATE TABLE idempotency_key (
    key text PRIMARY KEY
);
