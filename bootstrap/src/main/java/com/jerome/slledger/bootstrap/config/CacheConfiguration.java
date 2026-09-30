package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.adapter.persistence.cache.CaffeineTier;
import com.jerome.slledger.adapter.persistence.cache.PriceCodec;
import com.jerome.slledger.adapter.persistence.cache.RedisInvalidationBus;
import com.jerome.slledger.adapter.persistence.cache.RedisLock;
import com.jerome.slledger.adapter.persistence.cache.RedisPriceTier;
import com.jerome.slledger.application.cache.CachingMarketPriceSource;
import com.jerome.slledger.application.cache.CachingSecurityDirectory;
import com.jerome.slledger.application.cache.DistributedLock;
import com.jerome.slledger.application.cache.ReadThrough;
import com.jerome.slledger.application.cache.TtlPolicy;
import com.jerome.slledger.application.port.MarketPriceSource;
import com.jerome.slledger.application.port.SecurityDirectory;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.instrument.Security;
import com.jerome.slledger.domain.money.Money;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.LockSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The quote cache: L1 in Caffeine, L2 in Redis, a rebuild lock across instances, and an
 * invalidation channel between them. Only present when {@code ledger.cache.enabled=true}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ledger.cache", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfiguration {

  @Bean
  DistributedLock quoteRebuildLock(StringRedisTemplate redis, CacheProperties properties) {
    return new RedisLock(
        redis, properties.keyPrefix() + "lock:", properties.keyPrefix() + "fence:");
  }

  @Bean(destroyMethod = "close")
  RedisInvalidationBus priceInvalidationBus(
      RedisConnectionFactory connections, StringRedisTemplate redis, CacheProperties properties) {
    return new RedisInvalidationBus(connections, redis, properties.invalidationChannel());
  }

  @Bean
  CachedQuoteSources cachedQuoteSources(
      SecurityDirectory database,
      MarketPriceSource source,
      StringRedisTemplate redis,
      DistributedLock lock,
      RedisInvalidationBus bus,
      CacheProperties properties) {
    Random random = new Random();
    TtlPolicy l1 = new TtlPolicy(properties.l1Ttl(), properties.jitter(), properties.negativeTtl());
    TtlPolicy l2 = new TtlPolicy(properties.l2Ttl(), properties.jitter(), properties.negativeTtl());

    CachingSecurityDirectory securities =
        new CachingSecurityDirectory(
            database,
            List.<ReadThrough.Layer<Isin, Security>>of(
                new ReadThrough.Layer<>(new CaffeineTier<>(properties.l1MaxSize()), l1)),
            random);

    ReadThrough.Rebuild<Isin> rebuild =
        new ReadThrough.Rebuild<>(
            lock,
            isin -> "price:" + isin.value(),
            properties.lockTtl(),
            properties.lockPolls(),
            () -> LockSupport.parkNanos(properties.lockPollInterval().toNanos()));
    CachingMarketPriceSource prices =
        new CachingMarketPriceSource(
            source,
            List.<ReadThrough.Layer<Isin, Money>>of(
                new ReadThrough.Layer<>(new CaffeineTier<>(properties.l1MaxSize()), l1),
                new ReadThrough.Layer<>(
                    new RedisPriceTier(redis, properties.keyPrefix() + "price:", new PriceCodec()),
                    l2)),
            random,
            rebuild,
            bus);
    bus.start(prices::evictLocal);
    return new CachedQuoteSources(securities, prices);
  }
}
