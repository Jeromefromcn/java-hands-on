-- pool-rollup.sql for one pool's subtree only: the recursion starts from that pool alone, so it
-- touches that subtree and nothing else. Bound parameter: the pool id.
WITH RECURSIVE tree (root_id, pool_id) AS (
    SELECT id, id FROM collateral_pool WHERE id = ?
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
