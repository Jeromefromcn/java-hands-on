-- Stage 10. Events the ledger owes the outside world, written in the same transaction as the state
-- change that caused them and sent afterwards by a relay.
--
-- payload is text, not jsonb: what is stored is the exact bytes that will be sent. jsonb would
-- reorder keys and re-render numbers, and a Money amount is a string in the payload for a reason.
--
-- seq is the claim order. It is assigned at insert, not at commit, so across two concurrent
-- transactions it is not commit order; per key it is, because a key is a position and a position's
-- writes are serialised by its version. The claim query enforces per-key order on top of it.
CREATE TABLE outbox_event (
    id          uuid        PRIMARY KEY,
    seq         bigserial   NOT NULL UNIQUE,
    topic       text        NOT NULL,
    message_key text        NOT NULL,
    type        text        NOT NULL,
    payload     text        NOT NULL,
    created_at  timestamptz NOT NULL,
    published_at timestamptz
);

-- The relay reads only what is unpublished; a partial index stays small however much history there is.
CREATE INDEX outbox_event_unpublished ON outbox_event (seq) WHERE published_at IS NULL;
CREATE INDEX outbox_event_unpublished_key ON outbox_event (message_key, seq) WHERE published_at IS NULL;
