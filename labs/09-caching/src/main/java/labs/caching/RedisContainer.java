package labs.caching;

import org.testcontainers.containers.GenericContainer;

/** A throwaway Redis for one lab run. */
final class RedisContainer implements AutoCloseable {

  private final GenericContainer<?> container =
      new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

  RedisContainer() {
    container.start();
  }

  String host() {
    return container.getHost();
  }

  int port() {
    return container.getMappedPort(6379);
  }

  @Override
  public void close() {
    container.stop();
  }
}
