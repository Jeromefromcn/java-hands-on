package com.jerome.slledger.bootstrap.web;

import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY_2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.application.port.MarketDataUnavailableException;
import com.jerome.slledger.application.port.MarketPriceSource;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import com.jerome.slledger.domain.instrument.Isin;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A slow downstream with no read timeout takes the whole server with it: every Tomcat worker ends
 * up waiting on the market data source, and a request that never touches it cannot be served. This
 * is the pain stage 12's circuit breaker and bulkhead remove. See docs/decisions/07-web.md.
 */
@Timeout(120)
class ThreadExhaustionIT {

  private static final int WORKERS = 4;
  private static final StringBuilder EVIDENCE =
      new StringBuilder(
          "Stage 07 · section 15 — a hanging market data source (ThreadExhaustionIT)\n\n");

  private final HttpClient client = HttpClient.newHttpClient();

  @AfterAll
  static void write() {
    Evidence.write("07-thread-exhaustion.txt", EVIDENCE.toString());
  }

  @Test
  @DisplayName("no read timeout: four hanging quotes and the server cannot answer GET /loans")
  void reproducesThreadExhaustionWithoutTimeout() throws Exception {
    // See docs/decisions/07-web.md
    try (StubPriceServer source = StubPriceServer.start();
        WebLedger ledger = start(source, "--ledger.marketdata.read-timeout=0")) {
      source.hang(HK_EQUITY_2);
      List<CompletableFuture<HttpResponse<String>>> quotes = sendQuotes(ledger, WORKERS);
      try {
        awaitArrivals(source, WORKERS);

        long started = System.nanoTime();
        assertThatThrownBy(() -> send(ledger, "/loans", Duration.ofSeconds(2)))
            .isInstanceOf(HttpTimeoutException.class);
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        EVIDENCE.append(
            """
            1. No read timeout (ledger.marketdata.read-timeout=0), server.tomcat.threads.max=%d.
               %d quote requests arrived at the source and hang there.
               GET /loans, which never touches the source, sent with a 2 s client timeout:
               HttpTimeoutException after %d ms. No worker was free to read it.

               Thread dump at that moment (ThreadMXBean, full stacks), the application's Tomcat
               workers:

            """
                .formatted(WORKERS, source.arrived(), waited));
        EVIDENCE.append(threadDump());
      } finally {
        source.release();
      }
      for (CompletableFuture<HttpResponse<String>> quote : quotes) {
        quote.get(10, TimeUnit.SECONDS);
      }
      assertThat(send(ledger, "/loans", Duration.ofSeconds(5)).statusCode()).isEqualTo(200);
    }
  }

  @Test
  @DisplayName("a 500 ms read timeout: the quotes fail fast with 504 and GET /loans is served")
  void timeoutKeepsServerResponsive() throws Exception {
    try (StubPriceServer source = StubPriceServer.start();
        WebLedger ledger = start(source, "--ledger.marketdata.read-timeout=500ms")) {
      source.hang(HK_EQUITY_2);
      try {
        long started = System.nanoTime();
        List<CompletableFuture<HttpResponse<String>>> quotes = sendQuotes(ledger, WORKERS);
        awaitArrivals(source, WORKERS);
        long listSent = System.nanoTime();
        HttpResponse<String> list = send(ledger, "/loans", Duration.ofSeconds(5));
        long listMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - listSent);

        List<String> timings = new ArrayList<>();
        for (CompletableFuture<HttpResponse<String>> quote : quotes) {
          HttpResponse<String> answered = quote.get(10, TimeUnit.SECONDS);
          long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
          assertThat(answered.statusCode()).isEqualTo(504);
          assertThat(millis).isLessThan(2_000);
          timings.add(answered.statusCode() + " by " + millis + " ms");
        }
        assertThat(list.statusCode()).isEqualTo(200);

        EVIDENCE.append(
            """

            2. The same four hanging quotes with ledger.marketdata.read-timeout=500ms.
               Quotes (time since the first was sent, measured when the test collected each):
                 %s
               GET /loans, sent once all four were waiting on the source: %d in %d ms.
            """
                .formatted(String.join("\n     ", timings), list.statusCode(), listMillis));
      } finally {
        source.release();
      }
    }
  }

  @Test
  @DisplayName("an address that does not answer: 504 within the connect timeout's bound")
  void connectTimeoutObservation() throws Exception {
    try (WebLedger ledger =
        WebLedger.start(
            "--ledger.marketdata.base-url=http://10.255.255.1:80",
            "--ledger.resilience.marketdata.enabled=false", // stage 7/8 reproduce the unprotected
            // client
            "--ledger.marketdata.connect-timeout=500ms",
            "--ledger.marketdata.read-timeout=500ms")) {
      long started = System.nanoTime();
      HttpResponse<String> quote =
          send(ledger, "/securities/" + HK_EQUITY_2 + "/quote", Duration.ofSeconds(10));
      long httpMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertThat(quote.statusCode()).isEqualTo(504);
      assertThat(httpMillis).isLessThan(3_000);

      MarketPriceSource source = ledger.app.getBean(MarketPriceSource.class);
      long direct = System.nanoTime();
      Throwable failure = null;
      try {
        source.priceOf(new Isin(HK_EQUITY_2));
      } catch (MarketDataUnavailableException e) {
        failure = e;
      }
      long directMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - direct);
      assertThat(failure).isNotNull();

      List<String> causes = new ArrayList<>();
      for (Throwable t = failure; t != null; t = t.getCause()) {
        causes.add(t.getClass().getName() + ": " + t.getMessage());
      }
      EVIDENCE.append(
          """

          3. Base URL http://10.255.255.1:80 (non-routable), connect-timeout=500ms, read-timeout=5s.
             GET /securities/%s/quote: %d in %d ms.
             The adapter called directly failed after %d ms. Cause chain:
               %s
             Whether this is a connect timeout or an immediate refusal depends on this
             machine's network. With the read timeout at 5 s, a failure near 500 ms is the
             connect timeout; Spring's JDK request surfaces it as the HttpTimeoutException above.
          """
              .formatted(
                  HK_EQUITY_2,
                  quote.statusCode(),
                  httpMillis,
                  directMillis,
                  String.join("\n       ", causes)));
    }
  }

  private static WebLedger start(StubPriceServer source, String readTimeout) {
    return WebLedger.start(
        "--ledger.marketdata.base-url=" + source.baseUrl(),
        "--ledger.resilience.marketdata.enabled=false", // stage 7/8 reproduce the unprotected
        // client
        readTimeout,
        "--server.tomcat.threads.max=" + WORKERS,
        "--server.tomcat.threads.min-spare=" + WORKERS);
  }

  private List<CompletableFuture<HttpResponse<String>>> sendQuotes(WebLedger ledger, int count) {
    List<CompletableFuture<HttpResponse<String>>> quotes = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      quotes.add(
          client.sendAsync(
              HttpRequest.newBuilder(uri(ledger, "/securities/" + HK_EQUITY_2 + "/quote")).build(),
              HttpResponse.BodyHandlers.ofString()));
    }
    return quotes;
  }

  private HttpResponse<String> send(WebLedger ledger, String path, Duration timeout)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(ledger, path)).timeout(timeout).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static URI uri(WebLedger ledger, String path) {
    return URI.create("http://localhost:" + LedgerApp.port(ledger.app) + path);
  }

  /** Polls a condition with a bound; nothing is sequenced by time. */
  private static void awaitArrivals(StubPriceServer source, int count) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (source.arrived() < count) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("only " + source.arrived() + " quote requests reached the source");
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
    }
  }

  /**
   * Every Tomcat worker of the application under test, with full stacks, and what the JVM says
   * about deadlocks at the same moment.
   */
  private static String threadDump() {
    var threads = ManagementFactory.getThreadMXBean();
    List<ThreadInfo> workers =
        Arrays.stream(threads.dumpAllThreads(true, true))
            .filter(t -> t.getThreadName().startsWith("http-nio-"))
            .filter(t -> t.getThreadName().contains("-exec-"))
            .sorted(Comparator.comparing(ThreadInfo::getThreadName))
            .toList();
    StringBuilder dump = new StringBuilder();
    for (ThreadInfo t : workers) {
      dump.append(
          "   \"%s\" #%d %s%n".formatted(t.getThreadName(), t.getThreadId(), t.getThreadState()));
      if (t.getLockInfo() != null) {
        dump.append("      - parking to wait for ").append(t.getLockInfo()).append('\n');
      }
      for (StackTraceElement frame : t.getStackTrace()) {
        dump.append("        at ").append(frame).append('\n');
      }
      dump.append('\n');
    }
    long[] deadlocked = threads.findDeadlockedThreads();
    long waitingOnSource =
        workers.stream()
            .filter(
                t ->
                    Arrays.stream(t.getStackTrace())
                        .anyMatch(f -> f.getClassName().endsWith("RestMarketPriceSource")))
            .count();
    dump.append(
        """
           Summary: %d Tomcat workers, %d of them inside RestMarketPriceSource.priceOf, parked in
           CompletableFuture.get under JdkClientHttpRequest.executeInternal.
           ThreadMXBean.findDeadlockedThreads(): %s
        """
            .formatted(
                workers.size(),
                waitingOnSource,
                deadlocked == null ? "null (no deadlock)" : Arrays.toString(deadlocked)));
    return dump.toString();
  }
}
