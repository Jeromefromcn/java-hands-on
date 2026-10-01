package com.jerome.slledger.bootstrap.web;

import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY;
import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * One slow downstream and the rest of the API. Tomcat is given 8 worker threads and the source
 * answers after 3 s. Twelve concurrent {@code /quote} calls then either hold every worker for those
 * 3 s (no bulkhead), or take 4 of them and are refused beyond that (a bulkhead of 4). While they
 * run, {@code GET /loans}, which never touches the source, is timed.
 *
 * <p>Stage 8 built this pain with thread dumps; this is the same failure with the cure measured
 * next to it.
 */
@Timeout(180)
class SlowSourceStarvationIT {

  private static final int WORKERS = 8;
  private static final int QUOTE_CALLS = 12;
  private static final long SOURCE_DELAY_MS = 3_000;

  private record Run(long unrelatedMillis, int quote200, int quote503) {}

  private Run run(boolean bulkhead) throws Exception {
    try (StubPriceServer source = StubPriceServer.start();
        WebLedger ledger =
            WebLedger.start(
                "--server.tomcat.threads.max=" + WORKERS,
                "--ledger.marketdata.base-url=" + source.baseUrl(),
                "--ledger.marketdata.read-timeout=10s",
                "--ledger.marketdata.executor.deadline=15s",
                "--ledger.resilience.marketdata.enabled=" + bulkhead,
                "--ledger.resilience.marketdata.bulkhead.max-concurrent=4")) {
      source.slow(HK_EQUITY, SOURCE_DELAY_MS, "41.30", "HKD");
      String base = "http://localhost:" + LedgerApp.port(ledger.app);
      HttpClient client = HttpClient.newHttpClient();

      List<CompletableFuture<HttpResponse<String>>> quotes = new ArrayList<>();
      for (int i = 0; i < QUOTE_CALLS; i++) {
        quotes.add(
            client.sendAsync(
                HttpRequest.newBuilder(URI.create(base + "/securities/" + HK_EQUITY + "/quote"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()));
      }
      Thread.sleep(500); // real time: let the quote calls take their workers
      long start = System.nanoTime();
      HttpResponse<String> unrelated =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/loans")).build(),
              HttpResponse.BodyHandlers.ofString());
      long unrelatedMillis = (System.nanoTime() - start) / 1_000_000;
      assertThat(unrelated.statusCode()).isEqualTo(200);

      int ok = 0;
      int refused = 0;
      for (CompletableFuture<HttpResponse<String>> quote : quotes) {
        int status = quote.get(30, TimeUnit.SECONDS).statusCode();
        if (status == 200) {
          ok++;
        } else if (status == 503) {
          refused++;
        }
      }
      return new Run(unrelatedMillis, ok, refused);
    }
  }

  @Test
  @DisplayName("a slow source holds every worker unless a bulkhead bounds the calls into it")
  void reproducesSlowSourceStarvesUnrelatedEndpoint() throws Exception {
    // See docs/decisions/12-resilience-observability.md
    Run without = run(false);
    Run with = run(true);

    String report =
        """
        %d Tomcat workers, source answers after %d ms, %d concurrent /quote calls; GET /loans timed meanwhile
        no bulkhead : GET /loans took %d ms; /quote 200=%d 503=%d
        bulkhead 4  : GET /loans took %d ms; /quote 200=%d 503=%d
        """
            .formatted(
                WORKERS,
                SOURCE_DELAY_MS,
                QUOTE_CALLS,
                without.unrelatedMillis(),
                without.quote200(),
                without.quote503(),
                with.unrelatedMillis(),
                with.quote200(),
                with.quote503());
    System.out.println(report);
    Evidence.write("12-bulkhead-starvation.txt", report);

    // Wrong behaviour, asserted: the unrelated endpoint waited for a worker.
    assertThat(Duration.ofMillis(without.unrelatedMillis()))
        .isGreaterThan(Duration.ofMillis(1_500));
    // The fix: it did not, and the surplus was refused fast.
    assertThat(Duration.ofMillis(with.unrelatedMillis())).isLessThan(Duration.ofMillis(1_000));
    assertThat(with.quote503()).isEqualTo(QUOTE_CALLS - 4);
    assertThat(with.quote200()).isEqualTo(4);
  }
}
