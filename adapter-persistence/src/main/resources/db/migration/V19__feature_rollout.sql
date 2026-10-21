-- Stage 14. A change to a money rule is released behind a rollout: the percentage of counterparties
-- on the new behaviour. Raising it is proposed by one person and applied by another; lowering it is
-- one person. The rules are application code (RolloutRules); the table keeps two of them true even
-- for a writer that bypasses the application: the percentage is 0-100, and an applied change names
-- a checker who is not its maker. See docs/decisions/14-delivery.md.
CREATE TABLE feature_rollout (
    flag            text        PRIMARY KEY,
    percent         integer     NOT NULL CHECK (percent BETWEEN 0 AND 100),
    effective_since timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0
);

CREATE TABLE feature_rollout_change (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    flag         text        NOT NULL REFERENCES feature_rollout (flag),
    from_percent integer     NOT NULL CHECK (from_percent BETWEEN 0 AND 100),
    to_percent   integer     NOT NULL CHECK (to_percent BETWEEN 0 AND 100),
    proposed_by  text        NOT NULL,
    proposed_at  timestamptz NOT NULL,
    status       text        NOT NULL CHECK (status IN ('PROPOSED', 'APPLIED', 'SUPERSEDED')),
    decided_by   text,
    decided_at   timestamptz,
    CONSTRAINT feature_rollout_change_maker_is_not_checker
        CHECK (status <> 'APPLIED' OR (decided_by IS NOT NULL AND decided_by <> proposed_by))
);

CREATE INDEX feature_rollout_change_flag_idx ON feature_rollout_change (flag, id DESC);

-- The one flag of stage 14: accrue on each currency's money-market day count (ACT/360 for USD, EUR
-- and CHF) instead of ACT/365F for everything. Off for everyone until a raise is approved.
INSERT INTO feature_rollout (flag, percent, effective_since)
VALUES ('accrual.day-count-by-market', 0, now());

-- The audit trail learns rollout changes (V18 listed every action, subject and result it allows).
ALTER TABLE audit_event DROP CONSTRAINT audit_event_action_known;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_action_known
    CHECK (action IN ('OPEN', 'RETURN', 'ACCESS', 'ROLLOUT'));
ALTER TABLE audit_event DROP CONSTRAINT audit_event_subject_known;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_subject_known
    CHECK (subject IN ('POSITION', 'REQUEST', 'ENDPOINT', 'FLAG'));
ALTER TABLE audit_event DROP CONSTRAINT audit_event_result_known;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_result_known
    CHECK (result IN ('BOOKED', 'REJECTED', 'DENIED', 'PROPOSED', 'APPROVED', 'LOWERED'));
