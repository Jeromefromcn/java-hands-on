package com.jerome.slledger.application.messaging;

/**
 * A message that cannot be read. Retrying it reads it the same way, so it is not retried: it goes
 * to the dead-letter topic on the first failure.
 */
public class MessageDecodeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MessageDecodeException(String message) {
    super(message);
  }

  public MessageDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
