package com.jerome.slledger.application.messaging;

/**
 * Where a use case records an event it owes the outside world, inside its own transaction. The
 * event is committed or rolled back with the state change that caused it; a relay sends it after.
 */
public interface Outbox {

  /** Records nothing. What a use case gets when messaging is not configured. */
  Outbox NONE = event -> {};

  /** Must be called inside the caller's transaction. */
  void append(IntegrationEvent event);
}
