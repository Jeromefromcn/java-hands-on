package com.jerome.slledger.application.messaging;

/**
 * What applying one price update did.
 *
 * @param applied {@code false} for a duplicate or a stale update, which changes nothing
 * @param marginCalls how many {@code MarginCallRaised} were recorded in the outbox
 * @param skipped positions that could not be revalued (collateral in another currency): the price
 *     is a fact and one position's collateral must not block it
 * @param deferred the security has more open positions than the inline limit: the price was applied
 *     and a batch revaluation was requested, so {@code marginCalls} and {@code skipped} are zero
 *     here and are the batch's to report
 */
public record PriceApplication(boolean applied, int marginCalls, int skipped, boolean deferred) {

  static final PriceApplication IGNORED = new PriceApplication(false, 0, 0, false);

  public PriceApplication(boolean applied, int marginCalls, int skipped) {
    this(applied, marginCalls, skipped, false);
  }
}
