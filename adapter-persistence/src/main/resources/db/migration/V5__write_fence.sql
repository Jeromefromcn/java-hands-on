-- Stage 9. The highest fencing token admitted for each protected resource. A write guarded by a
-- distributed lock presents the token its lease carried; a token lower than the one stored belongs
-- to a holder whose lock has since passed to someone else, and is refused. One row per resource,
-- so admitting a token also serialises the writers of that resource for the rest of the transaction.
CREATE TABLE write_fence (
    resource text   PRIMARY KEY,
    token    bigint NOT NULL
);
