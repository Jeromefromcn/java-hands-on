package com.jerome.slledger.bootstrap.web;

import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import com.jerome.slledger.bootstrap.security.TestTokens;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code CallerRuns} against {@code Abort}, with the same four Tomcat workers and the same hanging
 * source. Under {@code CallerRuns} the rejected work runs on the Tomcat workers themselves, so the
 * executor's bound protects nothing and an unrelated endpoint starves. See
 * docs/decisions/08-concurrency.md.
 */
@Timeout(120)
class CallerRunsIT {

  private static final int WORKERS = 4;
  private static final String[] PARTS = new String[2];
  private static final String HEADER =
      """
          Stage 08 · section 5 — CallerRuns pushing the load back onto Tomcat (CallerRunsIT)

          Setup for both runs: server.tomcat.threads.max=%d, executor core=1 max=1 queue=1,
          read-timeout=0, a source that hangs every call. Four GET /quotes requests (one ISIN
          each) are sent, then GET /loans with a 2 s client timeout.

          """
          .formatted(WORKERS);

  private final HttpClient client = HttpClient.newHttpClient();

  @AfterAll
  static void write() {
    Evidence.write("08-caller-runs-starvation.txt", HEADER + PARTS[0] + "\n" + PARTS[1]);
  }

  @Test
  @DisplayName("caller-runs: rejected quotes run on Tomcat workers and GET /loans cannot be served")
  void reproducesCallerRunsPushingLoadOntoTheRequestThread() throws Exception {
    // See docs/decisions/08-concurrency.md
    try (StubPriceServer source = StubPriceServer.start();
        WebLedger ledger = start(source, "caller-runs")) {
      source.hang(HK_EQUITY);
      List<CompletableFuture<HttpResponse<String>>> quotes = sendQuotes(ledger);
      try {
        // One call on the pool's worker, two on Tomcat workers running rejected tasks themselves.
        awaitArrivals(source, 3);

        assertThatThrownBy(() -> send(ledger, "/loans", Duration.ofSeconds(2)))
            .isInstanceOf(HttpTimeoutException.class);

        List<ThreadInfo> tomcatInSource = tomcatWorkersInSource();
        assertThat(tomcatInSource).hasSize(2);
        PARTS[0] =
            """
            1. policy=caller-runs.
               Requests that reached the source: %d (1 on the market-data worker, 2 on Tomcat
               workers). GET /loans: HttpTimeoutException after 2 s, no worker was free.
               Tomcat workers whose stack contains RestMarketPriceSource.priceOf: %s
               (their own request threads, executing the task the executor refused.)

            """
                .formatted(
                    source.arrived(),
                    tomcatInSource.stream().map(ThreadInfo::getThreadName).toList());
      } finally {
        source.release();
      }
      for (CompletableFuture<HttpResponse<String>> quote : quotes) {
        quote.get(30, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  @DisplayName("abort: the refused quotes fail at once with 503 and GET /loans is served")
  void abortKeepsServerResponsive() throws Exception {
    try (StubPriceServer source = StubPriceServer.start();
        WebLedger ledger = start(source, "abort")) {
      source.hang(HK_EQUITY);
      // Two are admitted (one running, one queued); the next two are refused and answer at once.
      List<CompletableFuture<HttpResponse<String>>> quotes = sendQuotes(ledger);
      try {
        long started = System.nanoTime();
        awaitTrue(() -> quotes.stream().filter(CompletableFuture::isDone).count() == 2);
        long refusedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        List<Integer> immediate = new ArrayList<>();
        for (CompletableFuture<HttpResponse<String>> quote : quotes) {
          if (quote.isDone()) {
            immediate.add(quote.get().statusCode());
          }
        }
        assertThat(immediate).containsExactly(503, 503);

        long listStarted = System.nanoTime();
        HttpResponse<String> list = send(ledger, "/loans", Duration.ofSeconds(5));
        long listMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - listStarted);
        assertThat(list.statusCode()).isEqualTo(200);

        PARTS[1] =
            """
            2. policy=abort, same setup.
               Two quotes answered 503 (%d ms after sending); two are still waiting on the source.
               GET /loans: %d in %d ms.
            """
                .formatted(refusedMillis, list.statusCode(), listMillis);
      } finally {
        source.release();
      }
      for (CompletableFuture<HttpResponse<String>> quote : quotes) {
        quote.get(30, TimeUnit.SECONDS);
      }
    }
  }

  private static WebLedger start(StubPriceServer source, String policy) {
    return WebLedger.start(
        "--ledger.marketdata.base-url=" + source.baseUrl(),
        "--ledger.resilience.marketdata.enabled=false",
        "--ledger.timeouts.enforce=false", // read timeout 0 is the unbounded wait // stage 7/8
        // reproduce the unprotected
        // client
        "--ledger.marketdata.read-timeout=0",
        "--ledger.marketdata.executor.core=1",
        "--ledger.marketdata.executor.max=1",
        "--ledger.marketdata.executor.queue=1",
        "--ledger.marketdata.executor.policy=" + policy,
        "--ledger.marketdata.executor.deadline=60s",
        "--server.tomcat.threads.max=" + WORKERS,
        "--server.tomcat.threads.min-spare=" + WORKERS);
  }

  private List<CompletableFuture<HttpResponse<String>>> sendQuotes(WebLedger ledger) {
    List<CompletableFuture<HttpResponse<String>>> quotes = new ArrayList<>();
    for (int i = 0; i < WORKERS; i++) {
      quotes.add(
          client.sendAsync(
              TestTokens.request(uri(ledger, "/quotes?isin=" + HK_EQUITY)).build(),
              HttpResponse.BodyHandlers.ofString()));
    }
    return quotes;
  }

  private HttpResponse<String> send(WebLedger ledger, String path, Duration timeout)
      throws Exception {
    return client.send(
        TestTokens.request(uri(ledger, path)).timeout(timeout).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static URI uri(WebLedger ledger, String path) {
    return URI.create("http://localhost:" + LedgerApp.port(ledger.app) + path);
  }

  private static List<ThreadInfo> tomcatWorkersInSource() {
    return Arrays.stream(ManagementFactory.getThreadMXBean().dumpAllThreads(false, false))
        .filter(t -> t.getThreadName().startsWith("http-nio-"))
        .filter(t -> t.getThreadName().contains("-exec-"))
        .filter(
            t ->
                Arrays.stream(t.getStackTrace())
                    .anyMatch(f -> f.getClassName().endsWith("RestMarketPriceSource")))
        .toList();
  }

  private static void awaitArrivals(StubPriceServer source, int count) {
    awaitTrue(() -> source.arrived() >= count);
  }

  private static void awaitTrue(java.util.function.BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached");
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
    }
  }
}
