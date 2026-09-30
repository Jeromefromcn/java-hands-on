package com.jerome.slledger.application.messaging;

/** The outbox: encode the event once, and store it in the caller's transaction. */
public class StoredOutbox implements Outbox {

  private final EventCodec codec;
  private final OutboxStore store;

  public StoredOutbox(EventCodec codec, OutboxStore store) {
    this.codec = codec;
    this.store = store;
  }

  @Override
  public void append(IntegrationEvent event) {
    store.append(OutboxMessage.of(event, codec.encode(event)));
  }
}
