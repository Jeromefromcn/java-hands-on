package com.jerome.slledger.application.quote;

import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The most recent price obtained from the source for each security, kept so that a screen can still
 * show something when the source cannot be reached, and say how old it is.
 *
 * <p>Per instance and in memory on purpose: it exists for the moment dependencies fail, so it
 * depends on none. It is read only by {@link QuotePrice}; opening or returning a position never
 * sees it.
 */
public interface LastKnownPrices {

  /** Keeps nothing. */
  LastKnownPrices NONE =
      new LastKnownPrices() {
        @Override
        public void remember(Isin isin, Money price, Instant at) {}

        @Override
        public Optional<Stamped> find(Isin isin) {
          return Optional.empty();
        }
      };

  record Stamped(Money price, Instant at) {}

  void remember(Isin isin, Money price, Instant at);

  Optional<Stamped> find(Isin isin);

  /** Least recently used entries go first once {@code capacity} securities are held. */
  static LastKnownPrices bounded(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1, was " + capacity);
    }
    Map<Isin, Stamped> entries =
        new LinkedHashMap<>(16, 0.75f, true) {
          private static final long serialVersionUID = 1L;

          @Override
          protected boolean removeEldestEntry(Map.Entry<Isin, Stamped> eldest) {
            return size() > capacity;
          }
        };
    return new LastKnownPrices() {
      @Override
      public void remember(Isin isin, Money price, Instant at) {
        synchronized (entries) {
          entries.put(isin, new Stamped(price, at));
        }
      }

      @Override
      public Optional<Stamped> find(Isin isin) {
        synchronized (entries) {
          return Optional.ofNullable(entries.get(isin));
        }
      }
    };
  }
}
