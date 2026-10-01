-- Stage 13. A counterparty's own listing filters on counterparty_id and pages by id. The primary
-- key leads with id, so without this index every scoped page reads the whole table to find one
-- borrower's rows. (counterparty_id, id) serves both the filter and the keyset order.
CREATE INDEX loan_position_counterparty_idx ON loan_position (counterparty_id, id);
