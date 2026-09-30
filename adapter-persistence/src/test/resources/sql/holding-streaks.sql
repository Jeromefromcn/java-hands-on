-- Consecutive holding days per position, as gaps-and-islands over accrual_entry.business_date. A
-- position accrues on every day it is held, so a run of consecutive dates is one holding streak.
--
-- Self-joins only: a streak starts on an entry with no entry the day before (anti-join), ends on
-- an entry with no entry the day after, and each start pairs with the first end on or after it.
WITH starts AS (
    SELECT e.position_id, e.business_date AS start_date
    FROM accrual_entry e
    WHERE NOT EXISTS (SELECT 1 FROM accrual_entry p
                      WHERE p.position_id = e.position_id
                        AND p.business_date = e.business_date - 1)
), ends AS (
    SELECT e.position_id, e.business_date AS end_date
    FROM accrual_entry e
    WHERE NOT EXISTS (SELECT 1 FROM accrual_entry n
                      WHERE n.position_id = e.position_id
                        AND n.business_date = e.business_date + 1)
)
SELECT s.position_id,
       s.start_date,
       min(e.end_date)                    AS end_date,
       min(e.end_date) - s.start_date + 1 AS days
FROM starts s
JOIN ends e ON e.position_id = s.position_id AND e.end_date >= s.start_date
GROUP BY s.position_id, s.start_date
ORDER BY s.position_id, s.start_date
