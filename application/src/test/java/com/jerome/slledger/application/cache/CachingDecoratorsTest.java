package com.jerome.slledger.application.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.application.port.MarketDataRejectedException;
import com.jerome.slledger.application.port.MarketDataUnavailableException;
import com.jerome.slledger.application.port.MarketPriceSource;
import com.jerome.slledger.application.port.SecurityDirectory;
import com.jerome.slledger.application.position.UnknownReferenceException;
import com.jerome.slledger.application.quote.QuotePrice;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.instrument.Security;
import com.jerome.slledger.domain.money.Money;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CachingDecoratorsTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Currency USD = Currency.getInstance("USD");
  private static final Isin KNOWN = new Isin("HK0000000001");
  private static final Isin UNKNOWN = new Isin("HK9999999999");
  private static final TtlPolicy NEAR =
      new TtlPolicy(Duration.ofSeconds(5), 0, Duration.ofSeconds(2));

  private final AtomicLong clock = new AtomicLong();
  private final AtomicInteger directoryCalls = new AtomicInteger();
  private final AtomicInteger sourceCalls = new AtomicInteger();

  private final SecurityDirectory database =
      isin -> {
        directoryCalls.incrementAndGet();
        return isin.equals(KNOWN)
            ? Optional.of(new Security(KNOWN, "Synthetic HK Equity", HKD, Money.of("41.26", HKD)))
            : Optional.empty();
      };

  private <V> List<ReadThrough.Layer<Isin, V>> l1() {
    return List.of(new ReadThrough.Layer<>(new FakeTier<Isin, V>(clock), NEAR));
  }

  @Test
  void unknownIsinCostsOneLookup() {
    CachingSecurityDirectory directory =
        new CachingSecurityDirectory(database, l1(), new Random(1));

    for (int i = 0; i < 1_000; i++) {
      assertThat(directory.find(UNKNOWN)).isEmpty();
    }
    assertThat(directoryCalls).hasValue(1);

    clock.addAndGet(Duration.ofSeconds(3).toNanos());
    assertThat(directory.find(UNKNOWN)).isEmpty();
    assertThat(directoryCalls).hasValue(2);
  }

  @Test
  void reproducesPenetrationWithoutNegativeCache() throws Exception {
    // See docs/decisions/09-caching.md
    for (int i = 0; i < 1_000; i++) {
      assertThat(database.find(UNKNOWN)).isEmpty();
    }
    int withoutCache = directoryCalls.getAndSet(0);

    CachingSecurityDirectory directory =
        new CachingSecurityDirectory(database, l1(), new Random(1));
    for (int i = 0; i < 1_000; i++) {
      assertThat(directory.find(UNKNOWN)).isEmpty();
    }
    int withNegativeEntry = directoryCalls.get();

    assertThat(withoutCache).isEqualTo(1_000);
    assertThat(withNegativeEntry).isEqualTo(1);

    java.nio.file.Path directoryPath = java.nio.file.Path.of("target", "evidence");
    java.nio.file.Files.createDirectories(directoryPath);
    java.nio.file.Files.writeString(
        directoryPath.resolve("09-penetration.txt"),
        """
        Stage 9, section 6: 1,000 requests for one ISIN the ledger does not hold.
        Counted at the directory (the database read). Deterministic by construction.

        no cache                       : %d directory reads
        negative entry (2 s TTL)       : %d directory read

        The Bloom filter comparison is measured in labs/09-caching.
        """
            .formatted(withoutCache, withNegativeEntry));
  }

  @Test
  void knownSecurityIsCachedToo() {
    CachingSecurityDirectory directory =
        new CachingSecurityDirectory(database, l1(), new Random(1));

    directory.find(KNOWN);
    directory.find(KNOWN);

    assertThat(directoryCalls).hasValue(1);
  }

  @Test
  void unknownIsinAtTheSourceIsCachedAndStillRefused() {
    MarketPriceSource source =
        isin -> {
          sourceCalls.incrementAndGet();
          throw UnknownReferenceException.subject("security", isin.value());
        };
    CachingMarketPriceSource cached =
        new CachingMarketPriceSource(source, l1(), new Random(1), null, null);

    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> cached.priceOf(UNKNOWN))
          .isInstanceOf(UnknownReferenceException.class)
          .extracting(e -> ((UnknownReferenceException) e).kind())
          .isEqualTo("security");
    }
    assertThat(sourceCalls).hasValue(1);
  }

  @Test
  void sourceFailureIsNotCached() {
    AtomicInteger calls = new AtomicInteger();
    MarketPriceSource source =
        isin -> {
          if (calls.incrementAndGet() == 1) {
            throw new MarketDataUnavailableException("down", null);
          }
          return Money.of("41.26", HKD);
        };
    CachingMarketPriceSource cached =
        new CachingMarketPriceSource(source, l1(), new Random(1), null, null);

    assertThatThrownBy(() -> cached.priceOf(KNOWN))
        .isInstanceOf(MarketDataUnavailableException.class);
    assertThat(cached.priceOf(KNOWN)).isEqualTo(Money.of("41.26", HKD));
    assertThat(cached.priceOf(KNOWN)).isEqualTo(Money.of("41.26", HKD));

    assertThat(calls).hasValue(2);
  }

  @Test
  void cachedPriceStillFacesTheCurrencyCheck() {
    // The source prices a HKD security in USD. The decorator caches what the source said; the
    // check belongs to QuotePrice, above it, and runs on every call.
    MarketPriceSource source =
        isin -> {
          sourceCalls.incrementAndGet();
          return Money.of("5.30", USD);
        };
    CachingMarketPriceSource cached =
        new CachingMarketPriceSource(source, l1(), new Random(1), null, null);
    QuotePrice quote = new QuotePrice(database, cached);

    assertThatThrownBy(() -> quote.quote(KNOWN)).isInstanceOf(MarketDataRejectedException.class);
    assertThatThrownBy(() -> quote.quote(KNOWN)).isInstanceOf(MarketDataRejectedException.class);

    assertThat(sourceCalls).hasValue(1);
  }

  @Test
  void evictMakesTheNextReadLoadAgain() {
    MarketPriceSource source =
        isin -> {
          sourceCalls.incrementAndGet();
          return Money.of("41.26", HKD);
        };
    CachingMarketPriceSource cached =
        new CachingMarketPriceSource(source, l1(), new Random(1), null, null);

    cached.priceOf(KNOWN);
    cached.evict(KNOWN);
    cached.priceOf(KNOWN);

    assertThat(sourceCalls).hasValue(2);
  }
}
