-- Stage 13. Who did what to which subject, and how it ended. Ids and codes only: no amount, no
-- counterparty name, so the trail cannot disagree with the ledger and holds nothing the money
-- invariant protects. Append-only: the trigger refuses UPDATE and DELETE for every role including
-- the application's, so the trail is not something a bug or a compromised application account can
-- quietly rewrite. (A superuser can still drop the trigger; the answer to that is an external copy,
-- which is stage 14's and the decision record's problem.)
CREATE TABLE audit_event (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    actor       text        NOT NULL,
    action      text        NOT NULL,
    subject     text        NOT NULL,
    subject_id  text        NOT NULL,
    result      text        NOT NULL,
    reason      text        NOT NULL DEFAULT '',
    CONSTRAINT audit_event_action_known CHECK (action IN ('OPEN', 'RETURN', 'ACCESS')),
    CONSTRAINT audit_event_subject_known CHECK (subject IN ('POSITION', 'REQUEST', 'ENDPOINT')),
    CONSTRAINT audit_event_result_known CHECK (result IN ('BOOKED', 'REJECTED', 'DENIED'))
);

-- "What happened to this position / this request", newest first.
CREATE INDEX audit_event_subject_idx ON audit_event (subject_id, id DESC);

CREATE FUNCTION audit_event_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only (% refused)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_no_rewrite
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_append_only();
