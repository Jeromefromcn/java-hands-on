package com.jerome.slledger.bootstrap.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import com.jerome.slledger.bootstrap.security.TestTokens;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A request in flight when the application is told to stop, as a pod's SIGTERM tells it: the
 * context closes while a 3 s request is 0.5 s in.
 *
 * <p>See docs/decisions/14-delivery.md.
 */
@Timeout(180)
class ShutdownIT {

  private static final StringBuilder EVIDENCE = new StringBuilder();
  private static final long REQUEST_MILLIS = 3_000;

  @AfterAll
  static void writeEvidence() {
    Evidence.write("14-shutdown.txt", EVIDENCE.toString());
  }

  // See docs/decisions/14-delivery.md
  @Test
  void reproducesInFlightRequestCutOffByImmediateShutdown() throws Exception {
    Outcome outcome = closeDuringSlowRequest("--server.shutdown=immediate");

    assertThat(outcome.response()).isNull();
    assertThat(outcome.failure()).isInstanceOf(IOException.class);
    record("server.shutdown=immediate", outcome);
  }

  @Test
  void gracefulShutdownLetsTheRequestInFlightFinishAndAcceptsNoNewOne() throws Exception {
    Outcome outcome = closeDuringSlowRequest();

    assertThat(outcome.response()).isNotNull();
    assertThat(outcome.response().statusCode()).isEqualTo(200);
    assertThat(outcome.response().body()).isEqualTo("done after 3000 ms");
    assertThat(outcome.newRequestDuringDrain()).isInstanceOf(IOException.class);
    assertThat(outcome.closeMillis()).isGreaterThanOrEqualTo(REQUEST_MILLIS - 1_000);
    record("server.shutdown=graceful (application.yml)", outcome);
  }

  private record Outcome(
      HttpResponse<String> response,
      Throwable failure,
      Throwable newRequestDuringDrain,
      long closeMillis) {}

  private static void record(String label, Outcome outcome) {
    EVIDENCE
        .append(label)
        .append(": context closed 0.5 s into a 3 s request; close took ")
        .append(outcome.closeMillis())
        .append(" ms; the client got ")
        .append(
            outcome.response() != null
                ? outcome.response().statusCode() + " \"" + outcome.response().body() + "\""
                : outcome.failure().getClass().getSimpleName()
                    + ": "
                    + outcome.failure().getMessage())
        .append("; a new request during the close got ")
        .append(
            outcome.newRequestDuringDrain() == null
                ? "an answer"
                : outcome.newRequestDuringDrain().getClass().getSimpleName())
        .append("\n");
  }

  private static Outcome closeDuringSlowRequest(String... args) throws Exception {
    ConfigurableApplicationContext app = LedgerApp.startWeb(List.of(SlowController.class), args);
    int port = LedgerApp.port(app);
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    SlowController.entered = new CountDownLatch(1);
    CompletableFuture<HttpResponse<String>> inFlight =
        client.sendAsync(
            TestTokens.request(
                    URI.create("http://localhost:" + port + "/test/slow?millis=" + REQUEST_MILLIS))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(SlowController.entered.await(30, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(500);

    long started = System.nanoTime();
    CompletableFuture<Void> closing = CompletableFuture.runAsync(app::close);
    Thread.sleep(300);
    Throwable refused = null;
    if (!closing.isDone()) {
      try {
        // A fresh client, so the request opens a new connection rather than reusing one.
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()
            .send(
                TestTokens.request(URI.create("http://localhost:" + port + "/test/slow?millis=1"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
      } catch (IOException e) {
        refused = e;
      }
    }
    closing.get(60, TimeUnit.SECONDS);
    long closeMillis = (System.nanoTime() - started) / 1_000_000;

    try {
      return new Outcome(inFlight.get(30, TimeUnit.SECONDS), null, refused, closeMillis);
    } catch (ExecutionException e) {
      return new Outcome(null, e.getCause(), refused, closeMillis);
    }
  }
}
