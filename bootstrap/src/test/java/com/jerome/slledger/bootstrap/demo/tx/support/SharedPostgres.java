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

  public static synchronized String[] datasourceProperties() {
    if (!CONTAINER.isRunning()) {
      CONTAINER.start();
    }
    return new String[] {
      "spring.datasource.url=" + CONTAINER.getJdbcUrl(),
      "spring.datasource.username=" + CONTAINER.getUsername(),
      "spring.datasource.password=" + CONTAINER.getPassword(),
    };
  }
}
