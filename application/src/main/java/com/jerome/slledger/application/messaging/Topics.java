package com.jerome.slledger.application.messaging;

/** The topic names, in one place. Each has a dead-letter companion, {@code <name>.DLT}. */
public final class Topics {

  public static final String PRICE_UPDATES = "sl.price-updates";
  public static final String MARGIN_CALLS = "sl.margin-calls";
  public static final String POSITION_EVENTS = "sl.position-events";

  private Topics() {}

  public static String deadLetterOf(String topic) {
    return topic + ".DLT";
  }
}
