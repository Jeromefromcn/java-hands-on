package com.jerome.slledger.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Every way a token can be wrong is the same answer: a 401 problem that says nothing about why. */
class TokenRejectionIT {

  private static ConfigurableApplicationContext app;
  private static final Map<String, String> RESULTS = new LinkedHashMap<>();

  @BeforeAll
  static void start() {
    app = LedgerApp.startWeb();
  }

  @AfterAll
  static void stop() {
    Evidence.write(
        "13-token-rejection.txt",
        "Stage 13 · section 2 — what a request with a bad token gets (TokenRejectionIT)\n\n"
            + RESULTS.entrySet().stream()
                .map(e -> "%-34s %s".formatted(e.getKey(), e.getValue()))
                .collect(java.util.stream.Collectors.joining("\n"))
            + "\n");
    app.close();
  }

  private static void assertUnauthorized(String label, String authorization) {
    RestTestClient anonymous = LedgerApp.http(app, null);
    RestTestClient.RequestHeadersSpec<?> request = anonymous.get().uri("/loans");
    if (authorization != null) {
      request = request.header("Authorization", authorization);
    }
    var result = request.exchange().returnResult(String.class);

    assertThat(result.getStatus()).as(label).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(result.getResponseHeaders().getContentType())
        .as(label)
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result.getResponseHeaders().getFirst("WWW-Authenticate"))
        .as(label)
        .isEqualTo("Bearer");
    String traceId = result.getResponseHeaders().getFirst("X-Trace-Id");
    assertThat(traceId).as(label).matches("[0-9a-f]{32}");
    String body = result.getResponseBody();
    assertThat(body)
        .as(label)
        .contains("urn:sl-ledger:problem:unauthenticated")
        .contains(traceId)
        // Why a token failed is for the log, not for the caller.
        .doesNotContainIgnoringCase("expired")
        .doesNotContainIgnoringCase("issuer")
        .doesNotContainIgnoringCase("audience")
        .doesNotContainIgnoringCase("signature");
    RESULTS.put(label, "401 " + body.replaceAll("\\s+", " "));
  }

  @Test
  @DisplayName("no token")
  void noToken() {
    assertUnauthorized("no Authorization header", null);
  }

  @Test
  @DisplayName("malformed, expired, wrong issuer, wrong audience, foreign key: all 401")
  void badTokens() {
    assertUnauthorized("not a JWT", "Bearer not-a-jwt");
    assertUnauthorized("basic credentials", "Basic dXNlcjpwYXNz");
    assertUnauthorized(
        "expired",
        "Bearer "
            + TestTokens.builder()
                .roles("ledger-trader")
                .expiresAt(Instant.now().minusSeconds(3600))
                .build());
    assertUnauthorized(
        "wrong issuer",
        "Bearer "
            + TestTokens.builder()
                .roles("ledger-trader")
                .issuer("https://evil.example/realm")
                .build());
    assertUnauthorized(
        "wrong audience",
        "Bearer "
            + TestTokens.builder().roles("ledger-trader").audience("another-service").build());
    assertUnauthorized(
        "signed by a key the service does not trust",
        "Bearer " + TestTokens.builder().roles("ledger-trader").signedByStranger().build());
  }

  @Test
  @DisplayName("a valid token is served")
  void validToken() {
    LedgerApp.http(app, TestTokens.trader()).get().uri("/loans").exchange().expectStatus().isOk();
  }

  @Test
  @DisplayName("the two probes need no token; a bad token on a probe is still a 401")
  void probesArePublic() {
    RestTestClient anonymous = LedgerApp.http(app, null);
    anonymous.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
    anonymous
        .get()
        .uri("/actuator/health/liveness")
        .header("Authorization", "Bearer not-a-jwt")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    anonymous.get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
    anonymous.get().uri("/no-such-path").exchange().expectStatus().isUnauthorized();
  }
}
