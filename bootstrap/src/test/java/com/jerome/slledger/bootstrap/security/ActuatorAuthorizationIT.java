package com.jerome.slledger.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Who may read each actuator endpoint, on the application port and on a separate management port.
 */
class ActuatorAuthorizationIT {

  private static ConfigurableApplicationContext app;
  private static ConfigurableApplicationContext separate;
  private static final Map<String, String> TABLE = new LinkedHashMap<>();

  @BeforeAll
  static void start() {
    app = LedgerApp.startWeb();
    separate = LedgerApp.startWeb("--management.server.port=0");
  }

  @AfterAll
  static void stop() {
    Evidence.write(
        "13-actuator-authorization.txt",
        "Stage 13 · section 8 — actuator endpoints by caller (ActuatorAuthorizationIT)\n\n"
            + TABLE.entrySet().stream()
                .map(e -> "%-52s %s".formatted(e.getKey(), e.getValue()))
                .collect(java.util.stream.Collectors.joining("\n"))
            + "\n");
    app.close();
    separate.close();
  }

  private static EntityExchangeResult<String> get(RestTestClient client, String path) {
    return client.get().uri(path).exchange().returnResult(String.class);
  }

  private static int status(String label, RestTestClient client, String path) {
    int status = get(client, path).getStatus().value();
    TABLE.put(label + " " + path, Integer.toString(status));
    return status;
  }

  @Test
  @DisplayName("the probes need no token; the scrape needs the metrics role and no other")
  void probesAndScrape() {
    RestTestClient anonymous = LedgerApp.http(app, null);

    assertThat(status("no token", anonymous, "/actuator/health/liveness")).isEqualTo(200);
    assertThat(status("no token", anonymous, "/actuator/prometheus")).isEqualTo(401);
    assertThat(status("trader", LedgerApp.http(app, TestTokens.trader()), "/actuator/prometheus"))
        .isEqualTo(403);
    assertThat(status("ops", LedgerApp.http(app, TestTokens.ops()), "/actuator/prometheus"))
        .isEqualTo(403);
    assertThat(status("metrics", LedgerApp.http(app, TestTokens.metrics()), "/actuator/prometheus"))
        .isEqualTo(200);
    // The scraper's role is for the scrape: it reads no business endpoint.
    assertThat(status("metrics", LedgerApp.http(app, TestTokens.metrics()), "/loans"))
        .isEqualTo(403);
  }

  @Test
  @DisplayName(
      "health details name the dependencies: ledger-ops sees them, every other caller does not")
  void healthDetails() {
    status("no token", LedgerApp.http(app, null), "/actuator/health");
    assertThat(status("no token", LedgerApp.http(app, null), "/actuator/health")).isEqualTo(401);

    String asTrader =
        get(LedgerApp.http(app, TestTokens.trader()), "/actuator/health").getResponseBody();
    String asOps = get(LedgerApp.http(app, TestTokens.ops()), "/actuator/health").getResponseBody();

    assertThat(asTrader).doesNotContain("components").doesNotContain("\"db\"");
    assertThat(asOps).contains("components").contains("\"db\"");
    TABLE.put("trader /actuator/health body", asTrader);
    TABLE.put(
        "ops /actuator/health body (components only)",
        asOps.replaceAll("\\s+", " ").substring(0, Math.min(120, asOps.length())) + "...");
  }

  @Test
  @DisplayName("on a separate management port the same chain applies: no token is 401")
  void separateManagementPort() {
    int port = Integer.parseInt(separate.getEnvironment().getProperty("local.management.port"));
    RestTestClient management =
        RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

    int anonymous = status("management port, no token", management, "/actuator/prometheus");
    int withToken =
        status(
            "management port, metrics token",
            RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + TestTokens.metrics())
                .build(),
            "/actuator/prometheus");

    assertThat(anonymous).isEqualTo(401);
    assertThat(withToken).isEqualTo(200);
  }
}
