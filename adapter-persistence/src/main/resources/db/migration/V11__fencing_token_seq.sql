-- Stage 11. Fencing tokens are drawn here, in the database that checks them (write_fence), and not
-- from a counter in Redis. A Redis restart without persistence began its counters again at 1,
-- below the token this database had already admitted, and every later write was refused (stage 9,
-- "Redis restart resets the fence"). One sequence serves every resource: a token only has to grow
-- across acquisitions of the same resource, and a global sequence does that a fortiori.
--
-- nextval is not transactional: a token drawn by a holder that then rolls back is simply never
-- used, which a fence tolerates (it needs order, not density).
CREATE SEQUENCE fencing_token_seq;
