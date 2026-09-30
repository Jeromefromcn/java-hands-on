package com.jerome.slledger.application.messaging;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The outbox table. */
public interface OutboxStore {

  /**
   * Stores a message, unpublished.
   *
   * @throws org.springframework.transaction.IllegalTransactionStateException if there is no
   *     transaction to store it in: an outbox row written on its own commits before the state
   *     change it describes, which is the failure the outbox exists to remove
   */
  void append(OutboxMessage message);

  /**
   * Up to {@code limit} unpublished messages, oldest first, locked for the rest of the current
   * transaction. Rows another transaction has locked are skipped, so two relays do not wait for
   * each other. A message is not handed out while an older message with the same key is unpublished
   * and not in this claim, so one key's messages are never sent out of order; a key's backlog is
   * handed out together, oldest first.
   */
  List<OutboxMessage> claim(int limit);

  void markPublished(Collection<UUID> ids, Instant at);

  long countUnpublished();

  /** When the oldest unpublished message was created: how long the relay has been behind. */
  Optional<Instant> oldestUnpublishedSince();
}
