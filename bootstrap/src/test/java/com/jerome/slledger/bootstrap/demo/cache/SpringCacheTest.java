package com.jerome.slledger.bootstrap.demo.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * {@code @Cacheable}, {@code @CachePut} and {@code @CacheEvict} on a bean over a counting source. A
 * unit test: no server, no database.
 */
class SpringCacheTest {

  private static AnnotationConfigApplicationContext context() {
    return new AnnotationConfigApplicationContext(CacheDemoConfiguration.class);
  }

  @Test
  void cacheableCallsSourceOncePerKey() {
    try (var context = context()) {
      PriceService prices = context.getBean(PriceService.class);

      assertThat(prices.priceOf("A")).isEqualTo("price-of-A");
      assertThat(prices.priceOf("A")).isEqualTo("price-of-A");
      assertThat(prices.priceOf("B")).isEqualTo("price-of-B");

      assertThat(prices.sourceCalls()).isEqualTo(2);
    }
  }

  @Test
  void cachePutRefreshesWithoutReadingTheSource() {
    try (var context = context()) {
      PriceService prices = context.getBean(PriceService.class);
      prices.priceOf("A");

      prices.refresh("A", "fresh");

      assertThat(prices.priceOf("A")).isEqualTo("fresh");
      assertThat(prices.sourceCalls()).isEqualTo(1);
    }
  }

  @Test
  void cacheEvictForcesAReload() {
    try (var context = context()) {
      PriceService prices = context.getBean(PriceService.class);
      prices.priceOf("A");

      prices.forget("A");
      prices.priceOf("A");

      assertThat(prices.sourceCalls()).isEqualTo(2);
    }
  }

  @Test
  void nullResultIsCachedUnlessTheAnnotationGuardsIt() {
    try (var context = context()) {
      PriceService prices = context.getBean(PriceService.class);

      prices.priceOf("NONE1");
      prices.priceOf("NONE1");
      int guarded = prices.sourceCalls();
      prices.priceOfUnguarded("NONE2");
      prices.priceOfUnguarded("NONE2");
      int unguarded = prices.sourceCalls() - guarded;

      // With `unless = "#result == null"` an unknown key is asked about every time, which is the
      // penetration of section 6; without it the manager's default caches the null.
      assertThat(guarded).isEqualTo(2);
      assertThat(unguarded).isEqualTo(1);
    }
  }

  // The call priceOf(...) inside priceTwiceViaThis goes to `this`, the target object, never to the
  // proxy that holds the cache advice. The same cause as stage 5's self-invocation.
  // See docs/decisions/09-caching.md
  @Test
  void reproducesSelfInvocationBypassesCache() {
    try (var context = context()) {
      PriceService prices = context.getBean(PriceService.class);
      PriceCaller caller = context.getBean(PriceCaller.class);

      prices.priceTwiceViaThis("SELF");
      int viaThis = prices.sourceCalls();
      caller.priceTwiceViaProxy("PROXY");
      int viaProxy = prices.sourceCalls() - viaThis;

      assertThat(viaThis).isEqualTo(2);
      assertThat(viaProxy).isEqualTo(1);
      assertThat(context.getBean(PriceService.class).getClass().getName()).contains("CGLIB");

      Evidence.write(
          "09-self-invocation.txt",
          """
          Stage 9, section 4: two calls to the same @Cacheable method, counted at the source.
          Measured by SpringCacheTest.reproducesSelfInvocationBypassesCache.

          both calls made on `this` inside PriceService : %d source calls (the cache was bypassed)
          both calls made through another bean          : %d source call  (the proxy cached)
          bean class                                    : %s
          """
              .formatted(viaThis, viaProxy, prices.getClass().getName()));
    }
  }
}
