package com.jerome.slledger.application.messaging;

/** Puts a message on the bus and waits for the broker's acknowledgement. */
public interface EventSender {

  /**
   * @throws EventSendException if the broker did not acknowledge within the sender's bound; the
   *     message may or may not have arrived, and sending it again is safe because a consumer
   *     discards a message id it has seen
   */
  void send(OutboxMessage message);
}
