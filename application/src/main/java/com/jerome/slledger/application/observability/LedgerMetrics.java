package com.jerome.slledger.application.observability;

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
}
