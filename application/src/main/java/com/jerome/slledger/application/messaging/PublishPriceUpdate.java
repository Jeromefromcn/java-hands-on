package com.jerome.slledger.application.messaging;

/**
 * What a price feed's gateway calls: encode, send, wait for the ack. Deliberately not through the
 * outbox. A price update comes from a feed the ledger does not own, so no write of the ledger's
 * commits alongside it and there is nothing to keep consistent with; the feed retries on failure,
 * and the version gate makes a repeat harmless.
 */
public class PublishPriceUpdate {

  private final EventCodec codec;
  private final EventSender sender;

  public PublishPriceUpdate(EventCodec codec, EventSender sender) {
    this.codec = codec;
    this.sender = sender;
  }

  public void publish(IntegrationEvent.PriceUpdated update) {
    sender.send(OutboxMessage.of(update, codec.encode(update)));
  }
}
