-- Stage 12. The W3C trace context of the span that wrote the event, so that the relay (a different
-- thread, running later, after the request's span has ended) and the consumer can continue the
-- trace. Nullable: rows written before this migration, or with tracing off, have none, and a
-- consumer that finds none starts a trace of its own.
ALTER TABLE outbox_event ADD COLUMN traceparent text;
