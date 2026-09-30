package com.jerome.slledger.adapter.persistence.cache;

import com.jerome.slledger.application.cache.CacheTier;
import com.jerome.slledger.application.cache.Entry;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The shared tier: prices in Redis, one string value per ISIN, each with the TTL it was stored
 * with. Nothing is stored without one, so a bug elsewhere cannot leave a price in Redis for ever.
 *
 * <p>A value that does not decode is a miss. The caller then reloads and overwrites it.
 */
public final class RedisPriceTier implements CacheTier<Isin, Money> {

  private static final Duration SMALLEST_TTL = Duration.ofMillis(1);

  private final StringRedisTemplate redis;
  private final String keyPrefix;
  private final PriceCodec codec;

  public RedisPriceTier(StringRedisTemplate redis, String keyPrefix, PriceCodec codec) {
    this.redis = redis;
    this.keyPrefix = keyPrefix;
    this.codec = codec;
  }

  @Override
  public Optional<Entry<Money>> get(Isin key) {
    String json = redis.opsForValue().get(keyOf(key));
    return json == null ? Optional.empty() : codec.decode(json);
  }

  @Override
  public void put(Isin key, Entry<Money> entry, Duration ttl) {
    Duration atLeastOneMillisecond = ttl.compareTo(SMALLEST_TTL) < 0 ? SMALLEST_TTL : ttl;
    redis.opsForValue().set(keyOf(key), codec.encode(entry), atLeastOneMillisecond);
  }

  @Override
  public void evict(Isin key) {
    redis.delete(keyOf(key));
  }

  private String keyOf(Isin key) {
    return keyPrefix + key.value();
  }
}
