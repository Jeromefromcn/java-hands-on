-- The obvious rewrite of holding-streaks.sql: within a streak, date minus its row number is
-- constant, so that difference names the island.
SELECT position_id,
       min(business_date)                          AS start_date,
       max(business_date)                          AS end_date,
       max(business_date) - min(business_date) + 1 AS days
FROM (
    SELECT position_id,
           business_date,
           business_date - (row_number() OVER (PARTITION BY position_id
                                               ORDER BY business_date))::int AS island
    FROM accrual_entry
) numbered
GROUP BY position_id, island
ORDER BY position_id, start_date
