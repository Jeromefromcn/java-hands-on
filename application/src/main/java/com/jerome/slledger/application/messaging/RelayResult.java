package com.jerome.slledger.application.messaging;

/**
 * What one relay pass did.
 *
 * @param failure why it stopped early, or {@code null} if it did not
 */
public record RelayResult(int claimed, int sent, EventSendException failure) {

  public boolean complete() {
    return failure == null;
  }
}
