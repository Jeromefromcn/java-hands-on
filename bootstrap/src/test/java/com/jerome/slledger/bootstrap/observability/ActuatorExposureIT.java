package com.jerome.slledger.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.position.LedgerApp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

/** What the actuator shows to whoever can reach the port, and what it must not. */
@Timeout(120)
class ActuatorExposureIT {

  /** Stands for any secret that arrives through the environment. */
  private static final String SECRET = "s3cr3t-value-that-must-not-leak";

  private static ConfigurableApplicationContext app;
  private static RestTestClient http;

  @BeforeAll
  static void start() {
    app = LedgerApp.startWeb("--ledger.test.password=" + SECRET);
    http = RestTestClient.bindToServer().baseUrl("http://localhost:" + LedgerApp.port(app)).build();
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  private static String body(String path) {
    return http.get().uri(path).exchange().returnResult(String.class).getResponseBody();
  }

  private static HttpStatus status(String path) {
    return (HttpStatus) http.get().uri(path).exchange().returnResult(String.class).getStatus();
  }

  @Test
  @DisplayName("the liveness probe and the scrape are served")
  void healthAndScrape() {
    // Not /actuator/health: its aggregate includes Redis, which this context has none of.
    assertThat(status("/actuator/health/liveness")).isEqualTo(HttpStatus.OK);
    assertThat(body("/actuator/prometheus")).contains("jvm_memory_used_bytes");
  }

  @Test
  @DisplayName("env, beans and configprops are not exposed, and no exposed endpoint shows a secret")
  void configurationIsNotExposed() {
    assertThat(status("/actuator/env")).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(status("/actuator/beans")).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(status("/actuator/configprops")).isEqualTo(HttpStatus.NOT_FOUND);
    for (String path :
        new String[] {"/actuator", "/actuator/health", "/actuator/info", "/actuator/prometheus"}) {
      assertThat(body(path)).as(path).doesNotContain(SECRET);
    }
  }
}
