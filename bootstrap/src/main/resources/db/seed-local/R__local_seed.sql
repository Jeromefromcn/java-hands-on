-- Synthetic reference data for running the ledger by hand (the local profile only).
-- Stage 7. Counterparties and securities have no endpoints of their own yet, so without this a
-- manual run could not open a position. ISIN check digits are valid: the web layer verifies them.
-- Never loaded in tests or in prod: only application-local.yml lists this location.
INSERT INTO counterparty (id, name, credit_limit_amount, credit_limit_currency) VALUES
    ('CP-001', 'Synthetic Borrower One', 1000000.00, 'HKD'),
    ('CP-002', 'Synthetic Borrower Two', 1000000.00, 'USD')
ON CONFLICT (id) DO NOTHING;

INSERT INTO security (isin, name, currency, market_price_amount, market_price_currency) VALUES
    ('HK0000000007', 'Synthetic HK Equity', 'HKD', 41.26, 'HKD'),
    ('HK0000000015', 'Synthetic HK Equity Two', 'HKD', 7.50, 'HKD'),
    ('US0000000010', 'Synthetic US Equity', 'USD', 10.00, 'USD')
ON CONFLICT (isin) DO NOTHING;
