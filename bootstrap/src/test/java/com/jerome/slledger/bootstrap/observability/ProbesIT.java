package com.jerome.slledger.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.messaging.testing.Evidence;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Liveness and readiness against a data source that can be made to refuse connections. Redis is not
 * started for these contexts, so the aggregate health is DOWN throughout: the probes must say UP
 * regardless, and that is the point of keeping it out of their groups.
 */
@Timeout(180)
class ProbesIT {

  /** Flipped by a test; the wrapped data source then refuses every connection. */
  static final AtomicBoolean DATABASE_DOWN = new AtomicBoolean();

  static final List<ReadinessState> READINESS_EVENTS = new CopyOnWriteArrayList<>();

  /** Test-only sources, registered by class. Neither is a component of the application. */
  static class SwitchableDatabase {
    @Bean
    static BeanPostProcessor refusingDataSource() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
          if (!(bean instanceof DataSource real) || Proxy.isProxyClass(bean.getClass())) {
            return bean;
          }
          return Proxy.newProxyInstance(
              ProbesIT.class.getClassLoader(),
              new Class<?>[] {DataSource.class},
              (proxy, method, args) -> {
                if (method.getName().equals("getConnection") && DATABASE_DOWN.get()) {
                  throw new SQLException("connection refused (test)");
                }
                try {
                  return method.invoke(real, args);
                } catch (InvocationTargetException e) {
                  throw e.getCause();
                }
              });
        }
      };
    }

    @Bean
    ApplicationListener<AvailabilityChangeEvent<ReadinessState>> readinessRecorder() {
      return event -> READINESS_EVENTS.add(event.getState());
    }
  }

  private record Probe(HttpStatus status, String body) {}

  private static Probe get(RestTestClient http, String path) {
    EntityExchangeResult<String> result =
        http.get().uri(path).exchange().returnResult(String.class);
    return new Probe((HttpStatus) result.getStatus(), result.getResponseBody());
  }

  private static ConfigurableApplicationContext start(String... args) {
    DATABASE_DOWN.set(false);
    READINESS_EVENTS.clear();
    return LedgerApp.startWeb(List.of(SwitchableDatabase.class), args);
  }

  private static RestTestClient client(ConfigurableApplicationContext app) {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + LedgerApp.port(app)).build();
  }

  @Test
  @DisplayName(
      "readiness follows the database; liveness and the probes ignore Redis, which is down here")
  void readinessFollowsTheDatabaseOnly() {
    StringBuilder evidence = new StringBuilder();
    try (ConfigurableApplicationContext app = start()) {
      RestTestClient http = client(app);

      Probe liveness = get(http, "/actuator/health/liveness");
      Probe readiness = get(http, "/actuator/health/readiness");
      Probe aggregate = get(http, "/actuator/health");
      evidence.append("database up, redis down\n");
      evidence.append("  liveness  ").append(liveness).append('\n');
      evidence.append("  readiness ").append(readiness).append('\n');
      evidence.append("  aggregate ").append(aggregate).append('\n');
      assertThat(liveness.status()).isEqualTo(HttpStatus.OK);
      assertThat(readiness.status()).isEqualTo(HttpStatus.OK);
      assertThat(aggregate.status())
          .as("the aggregate includes Redis, which is why it is not a probe")
          .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

      DATABASE_DOWN.set(true);
      liveness = get(http, "/actuator/health/liveness");
      readiness = get(http, "/actuator/health/readiness");
      evidence.append("database down\n");
      evidence.append("  liveness  ").append(liveness).append('\n');
      evidence.append("  readiness ").append(readiness).append('\n');
      assertThat(readiness.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      assertThat(liveness.status())
          .as("restarting the process does not fix a database; liveness must not follow it")
          .isEqualTo(HttpStatus.OK);
    }
    Evidence.write("12-probes.txt", evidence.toString());
  }

  @Test
  @DisplayName("with Boot's default readiness group the database being down is reported as UP")
  void reproducesReadinessUpWhileDatabaseIsDown() {
    // See docs/decisions/12-resilience-observability.md
    // A load balancer that routes on this answer sends a rolling update's traffic to an instance
    // that cannot serve it: the new pod is "ready" the moment the context starts.
    try (ConfigurableApplicationContext app =
        start("--management.endpoint.health.group.readiness.include=readinessState")) {
      RestTestClient http = client(app);
      DATABASE_DOWN.set(true);

      Probe readiness = get(http, "/actuator/health/readiness");

      assertThat(readiness.status()).isEqualTo(HttpStatus.OK);
      assertThat(readiness.body()).contains("\"UP\"");
    }
  }

  @Test
  @DisplayName("on shutdown readiness becomes REFUSING_TRAFFIC before the context is gone")
  void readinessRefusesTrafficOnShutdown() {
    ConfigurableApplicationContext app = start();
    assertThat(READINESS_EVENTS).contains(ReadinessState.ACCEPTING_TRAFFIC);

    app.close();

    assertThat(READINESS_EVENTS).endsWith(ReadinessState.REFUSING_TRAFFIC);
  }
}
