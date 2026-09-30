-- Stage 10. The consumer-side guard against a message delivered twice. A consumer records the id of
-- each message it acts on, in the same transaction as the effect: the row and the effect commit
-- together or not at all. The primary key is the guard; consumer is part of it because two
-- consumers of one topic each act on a message once.
--
-- Rows must outlive the longest period a message can be redelivered (the topic's retention, since a
-- replay can only reach messages still in the log) and should not outlive it by much: the table
-- grows by one row per message per consumer for as long as they are kept.
CREATE TABLE consumed_message (
    consumer    text        NOT NULL,
    message_id  uuid        NOT NULL,
    consumed_at timestamptz NOT NULL,
    PRIMARY KEY (consumer, message_id)
);

CREATE INDEX consumed_message_consumed_at ON consumed_message (consumed_at);
