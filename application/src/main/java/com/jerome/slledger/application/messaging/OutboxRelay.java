package com.jerome.slledger.application.messaging;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends what the outbox holds. One pass claims a batch, sends it in order, and marks what the
 * broker acknowledged, all in one transaction.
 *
 * <p>It stops at the first send that fails and marks only what was sent before it: a message is
 * never marked without an ack, and the ones behind a failure are not sent ahead of it. It returns
 * the failure instead of throwing, because throwing would roll back the marks of the messages that
 * did go, and they would be sent again.
 *
 * <p>The guarantee is at-least-once. If the process dies after an ack and before this transaction
 * commits, the next pass sends the same messages again; the id each carries is what lets a consumer
 * recognise them. The transaction stays open for the length of the sends, so a batch is small and
 * the sender's wait is bounded.
 */
public class OutboxRelay {

  private final OutboxStore store;
  private final EventSender sender;
  private final Clock clock;
  private final int batchSize;

  public OutboxRelay(OutboxStore store, EventSender sender, Clock clock, int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be at least 1, was " + batchSize);
    }
    this.store = store;
    this.sender = sender;
    this.clock = clock;
    this.batchSize = batchSize;
  }

  @Transactional
  public RelayResult relayBatch() {
    List<OutboxMessage> claimed = store.claim(batchSize);
    List<UUID> sent = new ArrayList<>(claimed.size());
    EventSendException failure = null;
    for (OutboxMessage message : claimed) {
      try {
        sender.send(message);
        sent.add(message.id());
      } catch (EventSendException e) {
        failure = e;
        break;
      }
    }
    if (!sent.isEmpty()) {
      store.markPublished(sent, clock.instant());
    }
    return new RelayResult(claimed.size(), sent.size(), failure);
  }
}
