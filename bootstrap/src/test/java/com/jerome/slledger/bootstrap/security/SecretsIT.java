package com.jerome.slledger.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;

/**
 * Secrets reach the application through the environment, so the question is where they can come out
 * again: an actuator endpoint, or a log line. The sentinels stand for the Redis password, a Kafka
 * credential and a token that is not a valid one; none may appear anywhere a caller or an operator
 * can read.
 */
@ExtendWith(OutputCaptureExtension.class)
class SecretsIT {

  private static final String REDIS = "s3cr3t-redis-password-sentinel";
  private static final String KAFKA = "s3cr3t-kafka-jaas-sentinel";
  private static final String BEARER = "s3cr3t-bearer-sentinel";

  @Test
  @DisplayName(
      "no sentinel in any exposed endpoint, in the start-up logs, or in the logs of failing requests")
  void secretsStayInTheEnvironment(CapturedOutput output) {
    try (ConfigurableApplicationContext app =
        LedgerApp.startWeb(
            "--spring.data.redis.password=" + REDIS,
            "--spring.kafka.properties.sasl.jaas.config=" + KAFKA)) {
      StringBuilder seen = new StringBuilder();
      for (String path :
          List.of(
              "/actuator",
              "/actuator/health",
              "/actuator/info",
              "/actuator/env",
              "/actuator/configprops",
              "/actuator/beans",
              "/actuator/heapdump",
              "/actuator/threaddump",
              "/actuator/loggers",
              "/actuator/mappings",
              "/v3/api-docs")) {
        for (String token :
            List.of(TestTokens.staff(), TestTokens.metrics(), TestTokens.auditor())) {
          seen.append(
              LedgerApp.http(app, token)
                  .get()
                  .uri(path)
                  .exchange()
                  .returnResult(String.class)
                  .getResponseBody());
        }
      }
      seen.append(
          LedgerApp.http(app, TestTokens.metrics())
              .get()
              .uri("/actuator/prometheus")
              .exchange()
              .returnResult(String.class)
              .getResponseBody());
      // Requests that fail in each way a request can: bad token, forbidden, malformed, unknown.
      LedgerApp.http(app, BEARER).get().uri("/loans").exchange().returnResult(String.class);
      LedgerApp.http(app, TestTokens.auditor())
          .get()
          .uri("/loans")
          .exchange()
          .returnResult(String.class);
      LedgerApp.http(app, TestTokens.trader())
          .post()
          .uri("/loans")
          .header("Idempotency-Key", "k-secrets")
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"redis\":\"" + REDIS + "\"")
          .exchange()
          .returnResult(String.class);
      LedgerApp.http(app, TestTokens.trader())
          .get()
          .uri("/no-such/" + KAFKA)
          .exchange()
          .returnResult(String.class);

      assertThat(seen.toString())
          .doesNotContain(REDIS)
          .doesNotContain(KAFKA)
          .doesNotContain(BEARER);
      String logs = output.getAll();
      assertThat(logs).doesNotContain(REDIS).doesNotContain(KAFKA).doesNotContain(BEARER);
      Evidence.write(
          "13-secrets.txt",
          "Stage 13 · section 8 — where a secret can come out (SecretsIT)\n\n"
              + "Sentinels: a Redis password and a Kafka JAAS config given as properties, and a bearer\n"
              + "token that is not a JWT. Searched: 11 actuator and documentation paths as three\n"
              + "different roles, the scrape, and %d characters of captured log output covering\n"
                  .formatted(logs.length())
              + "start-up, a bad token, a 403, a malformed body and an unknown path.\n"
              + "Occurrences found: 0.\n");
    }
  }
}
