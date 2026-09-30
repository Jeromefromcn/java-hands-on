-- Test-only. A collateral pool hierarchy for the recursive-CTE exercise. Not one of the six domain
-- concepts, so it never enters a production migration.
CREATE TABLE collateral_pool (
    id        text PRIMARY KEY,
    parent_id text REFERENCES collateral_pool (id),
    name      text NOT NULL
);

CREATE INDEX collateral_pool_parent ON collateral_pool (parent_id);

-- Money as amount plus currency, as everywhere else.
CREATE TABLE pool_holding (
    pool_id  text          NOT NULL REFERENCES collateral_pool (id),
    amount   numeric(22,6) NOT NULL,
    currency char(3)       NOT NULL
);

CREATE INDEX pool_holding_pool ON pool_holding (pool_id);
