package com.jerome.slledger.application.messaging;

/** The bus did not acknowledge a message. The outcome is unknown, not "not sent". */
public class EventSendException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public EventSendException(String message, Throwable cause) {
    super(message, cause);
  }
}
