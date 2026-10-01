package com.jerome.slledger.application.observability;

import java.util.Currency;

/**
 * What the use cases tell the outside about what they did, as counts. Every method has an empty
 * default, so a test or a deployment with no metrics registry needs no implementation, and an
 * implementation must not throw: a metrics failure is not a reason to fail the work measured.
 *
 * <p>Nothing here takes a counterparty or a position id. A meter tagged with either has one series
 * per counterparty, and the metrics system pays for that for as long as it keeps the series.
 */
public interface LedgerMetrics {

  /** Records nothing. */
  LedgerMetrics NONE = new LedgerMetrics() {};

  /** How a price request ended. */
  enum QuoteResult {
    /** A price from the source, or from the cache in front of it. */
    LIVE,
    /** The source failed and a last-known price was returned, marked stale. */
    STALE,
    /** The source failed and there was nothing acceptable to fall back to. */
    FAILED
  }

  default void quoteServed(QuoteResult result) {}

  /**
   * A position was opened. Called from inside the booking's transaction: an implementation that
   * counts must count when that transaction commits, not when this is called, or a rolled-back open
   * is counted as an open.
   */
  default void positionOpened(Currency collateralCurrency) {}

  /** Margin calls were recorded; same rule about commits as {@link #positionOpened}. */
  default void marginCallsRaised(int count) {}

  /** A counterparty was told about a margin call. */
  default void noticeSent() {}

  /** A message was set aside in a dead-letter topic. */
  default void deadLettered(String topic) {}
}
