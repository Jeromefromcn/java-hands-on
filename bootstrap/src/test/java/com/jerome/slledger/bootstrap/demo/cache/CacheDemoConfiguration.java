package com.jerome.slledger.bootstrap.demo.cache;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;

/**
 * Test-only. A plain map-backed manager: what is under test is the proxy, not the cache engine.
 * Deliberately not a {@code @Configuration}: the application scans this package, and a demo
 * registered by class stays out of it ({@code ApplicationScanIT}).
 */
@EnableCaching
public class CacheDemoConfiguration {

  @Bean
  CacheManager cacheManager() {
    return new ConcurrentMapCacheManager();
  }

  @Bean
  PriceService priceService() {
    return new PriceService();
  }

  @Bean
  PriceCaller priceCaller(PriceService prices) {
    return new PriceCaller(prices);
  }
}
