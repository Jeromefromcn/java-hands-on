package com.jerome.slledger.adapter.persistence.testing;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * One Redis per test JVM, started on first use; Ryuk removes it at JVM exit. The same shape as
 * {@link SharedPostgres}, and for the same reason: containers are slow to start and tests are many.
 *
 * <p>Tests that share the server give themselves a key prefix and call {@link #flushAll} when they
 * finish. Published to {@code bootstrap}'s tests through this module's test-jar.
 */
public final class SharedRedis {

  private static final int REDIS_PORT = 6379;

  private static final GenericContainer<?> CONTAINER =
      new GenericContainer<>("redis:7.4-alpine").withExposedPorts(REDIS_PORT);

  private SharedRedis() {}

  public static synchronized void start() {
    if (!CONTAINER.isRunning()) {
      CONTAINER.start();
    }
  }

  public static synchronized String host() {
    start();
    return CONTAINER.getHost();
  }

  public static synchronized int port() {
    start();
    return CONTAINER.getMappedPort(REDIS_PORT);
  }

  /** A new client on the shared server; the caller closes it with {@link Client#close}. */
  public static Client client() {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(new RedisStandaloneConfiguration(host(), port()));
    factory.afterPropertiesSet();
    StringRedisTemplate template = new StringRedisTemplate(factory);
    template.afterPropertiesSet();
    return new Client(factory, template);
  }

  public static void flushAll() {
    try (Client client = client()) {
      client
          .template()
          .execute(
              (RedisCallback<Void>)
                  connection -> {
                    connection.serverCommands().flushAll();
                    return null;
                  });
    }
  }

  /** A connection factory and the template over it. */
  public record Client(LettuceConnectionFactory factory, StringRedisTemplate template)
      implements AutoCloseable {

    @Override
    public void close() {
      factory.destroy();
    }
  }
}
