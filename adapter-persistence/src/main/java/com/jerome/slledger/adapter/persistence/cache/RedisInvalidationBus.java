package com.jerome.slledger.adapter.persistence.cache;

import com.jerome.slledger.application.cache.Invalidation;
import com.jerome.slledger.domain.instrument.Isin;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis pub/sub as the invalidation channel between instances: the message is the ISIN, and the
 * subscriber's job is one call to drop the nearest-tier copy.
 *
 * <p>Pub/sub keeps no history. A message published while an instance is disconnected, or between
 * its disconnect and its resubscription, never reaches it; that instance serves its old copy until
 * the copy's own TTL. The channel shortens staleness in the ordinary case and bounds nothing in the
 * bad one.
 */
public final class RedisInvalidationBus implements Invalidation<Isin>, AutoCloseable {

  private final RedisConnectionFactory connections;
  private final StringRedisTemplate redis;
  private final String channel;
  private final AtomicLong refused = new AtomicLong();
  private RedisMessageListenerContainer container;

  public RedisInvalidationBus(
      RedisConnectionFactory connections, StringRedisTemplate redis, String channel) {
    this.connections = connections;
    this.redis = redis;
    this.channel = channel;
  }

  /** Subscribes; returns once the subscription is registered. */
  public synchronized void start(Consumer<Isin> onInvalidate) {
    if (container != null) {
      throw new IllegalStateException("already started");
    }
    RedisMessageListenerContainer listening = new RedisMessageListenerContainer();
    listening.setConnectionFactory(connections);
    listening.addMessageListener(
        (message, pattern) -> {
          try {
            onInvalidate.accept(new Isin(new String(message.getBody(), StandardCharsets.UTF_8)));
          } catch (RuntimeException notAnIsin) {
            refused.incrementAndGet();
          }
        },
        new ChannelTopic(channel));
    try {
      listening.afterPropertiesSet();
    } catch (Exception e) {
      throw new IllegalStateException("Could not start the invalidation listener", e);
    }
    listening.start();
    container = listening;
  }

  /**
   * Unsubscribes. Messages published from now until the next {@link #start} are lost to this
   * instance.
   */
  public synchronized void stop() {
    if (container != null) {
      container.stop();
      try {
        container.destroy();
      } catch (Exception e) {
        throw new IllegalStateException("Could not stop the invalidation listener", e);
      }
      container = null;
    }
  }

  @Override
  public void publish(Isin key) {
    redis.convertAndSend(channel, key.value());
  }

  /** Messages that were not an ISIN and were dropped. */
  public long refused() {
    return refused.get();
  }

  @Override
  public void close() {
    stop();
  }
}
