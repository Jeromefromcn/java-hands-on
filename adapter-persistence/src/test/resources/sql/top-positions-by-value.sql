-- Each counterparty's positions ranked by market value, three ways side by side, keeping every row
-- that any of the three puts in its top three (dense_rank <= 3 is the widest of the three).
--
-- Market value is outstanding quantity x the security's market price, so it is in the price's
-- currency, and positions are ranked within (counterparty, that currency): values in different
-- currencies are never compared.
--
-- ROW_NUMBER needs a tiebreaker, or which of two equal values comes first is up to the executor;
-- id is it. RANK and DENSE_RANK must NOT get the tiebreaker: their ties are defined by their own
-- ORDER BY, and with id in it nothing would ever tie.
SELECT counterparty_id, currency, id, market_value, rn, rnk, drnk
FROM (
    SELECT p.counterparty_id,
           s.market_price_currency                       AS currency,
           p.id,
           p.outstanding_quantity * s.market_price_amount AS market_value,
           row_number() OVER (PARTITION BY p.counterparty_id, s.market_price_currency
                              ORDER BY p.outstanding_quantity * s.market_price_amount DESC, p.id)
                                                         AS rn,
           rank()       OVER (PARTITION BY p.counterparty_id, s.market_price_currency
                              ORDER BY p.outstanding_quantity * s.market_price_amount DESC)
                                                         AS rnk,
           dense_rank() OVER (PARTITION BY p.counterparty_id, s.market_price_currency
                              ORDER BY p.outstanding_quantity * s.market_price_amount DESC)
                                                         AS drnk
    FROM loan_position p
    JOIN security s ON s.isin = p.isin
) ranked
WHERE drnk <= 3
ORDER BY counterparty_id, currency, market_value DESC, id
