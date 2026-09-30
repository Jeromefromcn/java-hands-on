package com.jerome.slledger.bootstrap.demo.cache;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;

/**
 * Stage 9's annotation demo. Not wired into the product, which uses an explicit read-through: the
 * annotations cannot jitter a TTL per entry or rebuild a hot key under a lock, and they are a
 * proxy. Prices are plain strings here; the demo is about the proxy, not the money.
 */
public class PriceService {

  private final AtomicInteger sourceCalls = new AtomicInteger();

  public int sourceCalls() {
    return sourceCalls.get();
  }

  @Cacheable(cacheNames = "prices", unless = "#result == null")
  public String priceOf(String isin) {
    sourceCalls.incrementAndGet();
    return isin.startsWith("NONE") ? null : "price-of-" + isin;
  }

  /** The same lookup with no {@code unless}: a {@code null} answer is cached too. */
  @Cacheable(cacheNames = "unguarded")
  public String priceOfUnguarded(String isin) {
    sourceCalls.incrementAndGet();
    return isin.startsWith("NONE") ? null : "price-of-" + isin;
  }

  @CachePut(cacheNames = "prices", key = "#isin")
  public String refresh(String isin, String newPrice) {
    return newPrice;
  }

  @CacheEvict(cacheNames = "prices")
  public void forget(String isin) {}

  /** Calls the cached method on {@code this}: the proxy is never involved. */
  public String priceTwiceViaThis(String isin) {
    return priceOf(isin) + "|" + priceOf(isin);
  }
}
