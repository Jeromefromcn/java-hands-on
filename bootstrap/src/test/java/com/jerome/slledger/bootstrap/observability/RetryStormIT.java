package com.jerome.slledger.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.bootstrap.security.TestTokens;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What an outer timeout shorter than the inner work does. A server takes 1 s per request; ten
 * callers each try up to three times. With a caller timeout of 300 ms every attempt is abandoned
 * while the server is still working on it, and the next attempt adds to it: the server sees thirty
 * requests in flight for ten that were meant. With a timeout that outlives the work it sees ten.
 */
@Timeout(120)
class RetryStormIT {

  private static final int CALLERS = 10;
  private static final int ATTEMPTS = 3;
  private static final long WORK_MILLIS = 1_000;

  private record Seen(int arrived, int peakInFlight, int answered) {}

  private static Seen run(Duration callerTimeout) throws Exception {
    AtomicInteger arrived = new AtomicInteger();
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    AtomicInteger answered = new AtomicInteger();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/work",
        exchange -> {
          arrived.incrementAndGet();
          peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
          try (exchange) {
            Thread.sleep(WORK_MILLIS); // real time: the work the caller gives up on
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            answered.incrementAndGet();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (IOException callerGone) {
            // The caller timed out and closed the connection.
          } finally {
            inFlight.decrementAndGet();
          }
        });
    server.start();
    ExecutorService callers = Executors.newFixedThreadPool(CALLERS);
    try {
      URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/work");
      HttpClient client = HttpClient.newHttpClient();
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < CALLERS; i++) {
        results.add(
            callers.submit(
                () -> {
                  for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                    try {
                      client.send(
                          TestTokens.request(uri).timeout(callerTimeout).build(),
                          HttpResponse.BodyHandlers.ofString());
                      return true;
                    } catch (HttpTimeoutException timedOut) {
                      // try again, as a retrying client does
                    }
                  }
                  return false;
                }));
      }
      for (Future<Boolean> result : results) {
        result.get(30, TimeUnit.SECONDS);
      }
      Thread.sleep(WORK_MILLIS + 200); // let the abandoned handlers finish and be counted
      return new Seen(arrived.get(), peak.get(), answered.get());
    } finally {
      callers.shutdownNow();
      server.stop(0);
    }
  }

  @Test
  @DisplayName("a caller timeout shorter than the work multiplies the load it meant to send")
  void reproducesRetryStormWhenOuterTimeoutIsShorter() throws Exception {
    // See docs/decisions/12-resilience-observability.md
    Seen storm = run(Duration.ofMillis(300));
    Seen budgeted = run(Duration.ofSeconds(3));

    String report =
        """
        %d callers, up to %d attempts each, server work %d ms
        caller timeout 300 ms (shorter than the work): server saw %d requests, peak %d in flight, completed %d
        caller timeout 3 s (outlives the work)       : server saw %d requests, peak %d in flight, completed %d
        """
            .formatted(
                CALLERS,
                ATTEMPTS,
                WORK_MILLIS,
                storm.arrived(),
                storm.peakInFlight(),
                storm.answered(),
                budgeted.arrived(),
                budgeted.peakInFlight(),
                budgeted.answered());
    System.out.println(report);
    Evidence.write("12-retry-storm.txt", report);

    // The wrong behaviour, asserted: three times the intended load, none of it useful to a caller.
    assertThat(storm.arrived()).isEqualTo(CALLERS * ATTEMPTS);
    assertThat(storm.peakInFlight()).isGreaterThan(CALLERS);
    // With the budget respected the load is what was meant.
    assertThat(budgeted.arrived()).isEqualTo(CALLERS);
    assertThat(budgeted.peakInFlight()).isLessThanOrEqualTo(CALLERS);
  }
}
