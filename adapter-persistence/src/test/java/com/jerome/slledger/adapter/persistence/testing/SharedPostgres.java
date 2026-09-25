package com.jerome.slledger.bootstrap.demo.tx.support;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One Postgres per test JVM, started on first use; Ryuk removes it at JVM exit.
 *
 * <p>A static singleton rather than {@code @ServiceConnection}: a container bean is bound to one
 * context and stopped when that context closes, and this stage starts many short-lived contexts
 * against the same database. Plain {@code spring.datasource.*} properties do the same job.
 */
public final class SharedPostgres {

  private static final PostgreSQLContainer CONTAINER =
      new PostgreSQLContainer("postgres:17-alpine");

  private SharedPostgres() {}

  /**
   * Starts the container if it is not running yet. Tests with a {@code @Timeout} call this from
   * {@code @BeforeAll}, so a first image pull and container start are not charged to a test.
   */
  public static synchronized void start() {
    if (!CONTAINER.isRunning()) {
      CONTAINER.start();
    }
  }

  public static synchronized String[] datasourceProperties() {
    start();
    return new String[] {
      "spring.datasource.url=" + CONTAINER.getJdbcUrl(),
      "spring.datasource.username=" + CONTAINER.getUsername(),
      "spring.datasource.password=" + CONTAINER.getPassword(),
    };
  }
}
