-- Every pool's holdings rolled up over its whole subtree, one line per (pool, currency): amounts in
-- different currencies are never added together.
--
-- tree pairs each pool (as a root) with every descendant, itself included. CYCLE stops a lineage
-- the first time it revisits a pool, so a parent_id loop written by mistake ends the recursion
-- instead of running forever; the revisiting row is marked and left out.
WITH RECURSIVE tree (root_id, pool_id) AS (
    SELECT id, id FROM collateral_pool
  UNION ALL
    SELECT t.root_id, c.id
    FROM tree t
    JOIN collateral_pool c ON c.parent_id = t.pool_id
) CYCLE pool_id SET is_cycle USING path
SELECT t.root_id, h.currency, sum(h.amount) AS total
FROM tree t
JOIN pool_holding h ON h.pool_id = t.pool_id
WHERE NOT t.is_cycle
GROUP BY t.root_id, h.currency
ORDER BY t.root_id, h.currency
