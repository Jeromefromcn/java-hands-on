package com.jerome.slledger.application.messaging;

import com.jerome.slledger.application.observability.Tracing;

/**
 * The outbox: encode the event once, and store it, with the trace context of the span that is
 * writing it, in the caller's transaction.
 */
public class StoredOutbox implements Outbox {

  private final EventCodec codec;
  private final OutboxStore store;
  private final Tracing tracing;

  /** No trace context is stored: what the outbox did before stage 12. */
  public StoredOutbox(EventCodec codec, OutboxStore store) {
    this(codec, store, Tracing.NONE);
  }

  public StoredOutbox(EventCodec codec, OutboxStore store, Tracing tracing) {
    this.codec = codec;
    this.store = store;
    this.tracing = tracing;
  }

  @Override
  public void append(IntegrationEvent event) {
    store.append(
        OutboxMessage.of(event, codec.encode(event), tracing.currentTraceparent().orElse(null)));
  }
}
