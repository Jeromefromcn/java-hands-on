package com.jerome.slledger.application.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.application.observability.LedgerMetrics;
import com.jerome.slledger.application.observability.LedgerMetrics.QuoteResult;
import com.jerome.slledger.application.port.MarketDataRejectedException;
import com.jerome.slledger.application.port.MarketDataUnavailableException;
import com.jerome.slledger.application.port.MarketPriceSource;
import com.jerome.slledger.application.position.UnknownReferenceException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.instrument.Security;
import com.jerome.slledger.domain.money.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a quote does when the source fails, against fakes and a clock the test moves. */
class QuotePriceDegradationTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Currency USD = Currency.getInstance("USD");
  private static final Isin ISIN = new Isin("HK0000000007");
  private static final Duration MAX_AGE = Duration.ofMinutes(15);

  /** A clock the test moves. */
  private static final class MovableClock extends Clock {
    private Instant now = Instant.parse("2026-10-01T02:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final MovableClock clock = new MovableClock();
  private final AtomicReference<java.util.function.Supplier<Money>> source =
      new AtomicReference<>(() -> Money.of("41.30", HKD));
  private final List<QuoteResult> results = new ArrayList<>();
  private final LedgerMetrics metrics =
      new LedgerMetrics() {
        @Override
        public void quoteServed(QuoteResult result) {
          results.add(result);
        }
      };

  private QuotePrice quote(Currency currency, Duration maxAge) {
    MarketPriceSource priced = isin -> source.get().get();
    return new QuotePrice(
        isin -> Optional.of(new Security(isin, "Synthetic", currency, Money.zero(currency))),
        priced,
        LastKnownPrices.bounded(10),
        clock,
        maxAge,
        metrics);
  }

  private void sourceFails(RuntimeException failure) {
    source.set(
        () -> {
          throw failure;
        });
  }

  @Test
  @DisplayName("a price from the source is live, and is what the next failure falls back to")
  void liveThenStale() {
    QuotePrice quote = quote(HKD, MAX_AGE);

    Quote live = quote.quote(ISIN);
    Instant obtained = clock.instant();
    clock.advance(Duration.ofMinutes(3));
    sourceFails(new MarketDataUnavailableException("down", null));
    Quote stale = quote.quote(ISIN);

    assertThat(live.stale()).isFalse();
    assertThat(live.asOf()).isNull();
    assertThat(stale.stale()).isTrue();
    assertThat(stale.price()).isEqualTo(Money.of("41.30", HKD));
    assertThat(stale.asOf())
        .as("when the price was true, not when it was served")
        .isEqualTo(obtained);
    assertThat(results).containsExactly(QuoteResult.LIVE, QuoteResult.STALE);
  }

  @Test
  @DisplayName("a refusal of the source's answer degrades like an outage; our own refusal does too")
  void everyFailureOfTheSourceDegrades() {
    QuotePrice quote = quote(HKD, MAX_AGE);
    quote.quote(ISIN);

    sourceFails(new MarketDataRejectedException("500"));
    assertThat(quote.quote(ISIN).stale()).isTrue();
    sourceFails(new OverloadedException("bulkhead full"));
    assertThat(quote.quote(ISIN).stale()).isTrue();
  }

  @Test
  @DisplayName("past the maximum age the original failure is thrown, not the old price")
  void tooOldIsAnError() {
    QuotePrice quote = quote(HKD, MAX_AGE);
    quote.quote(ISIN);
    clock.advance(MAX_AGE.plusSeconds(1));
    sourceFails(new MarketDataUnavailableException("down", null));

    assertThatThrownBy(() -> quote.quote(ISIN)).isInstanceOf(MarketDataUnavailableException.class);
    assertThat(results).containsExactly(QuoteResult.LIVE, QuoteResult.FAILED);
  }

  @Test
  @DisplayName("exactly at the maximum age it is still served")
  void atTheMaximumAge() {
    QuotePrice quote = quote(HKD, MAX_AGE);
    quote.quote(ISIN);
    clock.advance(MAX_AGE);
    sourceFails(new MarketDataUnavailableException("down", null));

    assertThat(quote.quote(ISIN).stale()).isTrue();
  }

  @Test
  @DisplayName("with nothing remembered, the failure is thrown")
  void nothingToFallBackTo() {
    QuotePrice quote = quote(HKD, MAX_AGE);
    sourceFails(new MarketDataUnavailableException("down", null));

    assertThatThrownBy(() -> quote.quote(ISIN)).isInstanceOf(MarketDataUnavailableException.class);
  }

  @Test
  @DisplayName("a remembered price in another currency than the security's is not served")
  void wrongCurrencyIsNotServed() {
    // The ledger now says this security trades in USD; the remembered price is in HKD.
    MarketPriceSource priced = isin -> source.get().get();
    LastKnownPrices store = LastKnownPrices.bounded(10);
    store.remember(ISIN, Money.of("41.30", HKD), clock.instant());
    QuotePrice quote =
        new QuotePrice(
            isin -> Optional.of(new Security(isin, "Synthetic", USD, Money.zero(USD))),
            priced,
            store,
            clock,
            MAX_AGE,
            metrics);
    sourceFails(new MarketDataUnavailableException("down", null));

    assertThatThrownBy(() -> quote.quote(ISIN)).isInstanceOf(MarketDataUnavailableException.class);
  }

  @Test
  @DisplayName(
      "an unknown security is never stale, and a live price in the wrong currency is refused")
  void unknownAndMismatchAreNotDegraded() {
    QuotePrice quote = quote(HKD, MAX_AGE);
    quote.quote(ISIN);

    sourceFails(UnknownReferenceException.subject("security", ISIN.value()));
    assertThatThrownBy(() -> quote.quote(ISIN)).isInstanceOf(UnknownReferenceException.class);

    source.set(() -> Money.of("5.00", USD));
    assertThatThrownBy(() -> quote.quote(ISIN)).isInstanceOf(MarketDataRejectedException.class);
  }

  @Test
  @DisplayName("a zero maximum age switches degradation off")
  void zeroMeansOff() {
    QuotePrice quote = quote(HKD, Duration.ZERO);
    quote.quote(ISIN);
    sourceFails(new MarketDataUnavailableException("down", null));

    assertThatThrownBy(() -> quote.quote(ISIN)).isInstanceOf(MarketDataUnavailableException.class);
  }

  @Test
  @DisplayName("the last-known store forgets its least recently used security beyond its capacity")
  void storeIsBounded() {
    LastKnownPrices store = LastKnownPrices.bounded(2);
    Isin a = new Isin("HK0000000001");
    Isin b = new Isin("HK0000000002");
    Isin c = new Isin("HK0000000003");
    Money price = Money.of("1.00", HKD);
    store.remember(a, price, clock.instant());
    store.remember(b, price, clock.instant());
    store.find(a); // a is now more recent than b
    store.remember(c, price, clock.instant());

    assertThat(store.find(b)).isEmpty();
    assertThat(store.find(a)).isPresent();
    assertThat(store.find(c)).isPresent();
  }
}
