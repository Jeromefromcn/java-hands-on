package com.jerome.slledger.bootstrap.observability;

import com.jerome.slledger.application.observability.LedgerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Currency;

/**
 * {@link LedgerMetrics} on Micrometer. Counters that state a fact about committed data are
 * registered for the commit of the surrounding transaction; the quote counter is not about data and
 * counts at once.
 *
 * <p>Tags are the currency and the outcome. Never an id: see {@link LedgerMetrics}.
 */
public final class MicrometerLedgerMetrics implements LedgerMetrics {

  private final MeterRegistry registry;

  public MicrometerLedgerMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void quoteServed(QuoteResult result) {
    registry
        .counter(
            "ledger.quote.requests", "result", result.name().toLowerCase(java.util.Locale.ROOT))
        .increment();
  }

  @Override
  public void positionOpened(Currency collateralCurrency) {
    AfterCommit.run(
        () ->
            registry
                .counter(
                    "ledger.positions.opened", "currency", collateralCurrency.getCurrencyCode())
                .increment());
  }

  @Override
  public void marginCallsRaised(int count) {
    if (count > 0) {
      AfterCommit.run(() -> registry.counter("ledger.margin.calls.raised").increment(count));
    }
  }

  @Override
  public void noticeSent() {
    AfterCommit.run(() -> registry.counter("ledger.notices.sent").increment());
  }

  @Override
  public void deadLettered(String topic) {
    registry.counter("ledger.dead.letters", "topic", topic).increment();
  }
}
