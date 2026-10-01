package com.jerome.slledger.bootstrap.web;

import static com.jerome.slledger.bootstrap.web.WebLedger.HK_EQUITY;
import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.config.MarketDataPool;
import com.jerome.slledger.bootstrap.config.PoolStats;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A slow source fills the fan-out's bounded executor: both workers wait on the source, the queue is
 * full, and the next request is refused. The thread dump taken at that moment is the evidence for
 * telling a full pool from a deadlock. See docs/decisions/08-concurrency.md.
 */
@Timeout(120)
class MarketDataPoolExhaustionIT {

  private final HttpClient client = HttpClient.newHttpClient();

  @Test
  @DisplayName("hanging source, 2 workers and a queue of 2: the fifth request is refused with 503")
  void reproducesPoolExhaustionWithHangingSource() throws Exception {
    // See docs/decisions/08-concurrency.md
    try (StubPriceServer source = StubPriceServer.start();
        WebLedger ledger =
            WebLedger.start(
                "--ledger.marketdata.base-url=" + source.baseUrl(),
                "--ledger.resilience.marketdata.enabled=false",
                "--ledger.timeouts.enforce=false", // read timeout 0 is the unbounded wait // stage
                // 7/8 reproduce the
                // unprotected client
                "--ledger.marketdata.read-timeout=0",
                "--ledger.marketdata.executor.core=2",
                "--ledger.marketdata.executor.max=2",
                "--ledger.marketdata.executor.queue=2",
                "--ledger.marketdata.executor.policy=abort",
                "--ledger.marketdata.executor.deadline=60s")) {
      source.hang(HK_EQUITY);
      MarketDataPool pool = ledger.app.getBean(MarketDataPool.class);
      List<CompletableFuture<HttpResponse<String>>> admitted = new ArrayList<>();
      try {
        for (int i = 0; i < 4; i++) {
          admitted.add(send(ledger));
        }
        awaitTrue(() -> pool.stats().active() == 2 && pool.stats().queued() == 2);
        awaitTrue(() -> source.arrived() == 2);

        HttpResponse<String> refused = send(ledger).get(10, TimeUnit.SECONDS);
        PoolStats stats = pool.stats();

        assertThat(refused.statusCode()).isEqualTo(503);
        assertThat(stats.rejected()).isEqualTo(1);
        assertThat(stats.active()).isEqualTo(2);
        assertThat(stats.queued()).isEqualTo(2);
        assertThat(source.arrived())
            .as("only the two running tasks reached the source")
            .isEqualTo(2);

        Evidence.write("08-pool-exhaustion-jstack.txt", evidence(stats, refused, source));
      } finally {
        source.release();
      }
      for (CompletableFuture<HttpResponse<String>> request : admitted) {
        assertThat(request.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      }
      assertThat(pool.stats().active()).isLessThanOrEqualTo(2);
    }
  }

  private CompletableFuture<HttpResponse<String>> send(WebLedger ledger) {
    return client.sendAsync(
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + com.jerome.slledger.bootstrap.position.LedgerApp.port(ledger.app)
                        + "/quotes?isin="
                        + HK_EQUITY))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String evidence(
      PoolStats stats, HttpResponse<String> refused, StubPriceServer source) {
    var threads = ManagementFactory.getThreadMXBean();
    List<ThreadInfo> all =
        Arrays.stream(threads.dumpAllThreads(true, true))
            .sorted(Comparator.comparing(ThreadInfo::getThreadName))
            .toList();
    List<ThreadInfo> pool =
        all.stream().filter(t -> t.getThreadName().startsWith("market-data-")).toList();
    List<ThreadInfo> tomcat =
        all.stream()
            .filter(t -> t.getThreadName().startsWith("http-nio-"))
            .filter(t -> t.getThreadName().contains("-exec-"))
            .filter(
                t ->
                    Arrays.stream(t.getStackTrace())
                        .anyMatch(f -> f.getClassName().endsWith("PriceFanOut")))
            .toList();
    StringBuilder out = new StringBuilder();
    out.append(
        """
        Stage 08 · section 4 — the market data executor full, source hanging (MarketDataPoolExhaustionIT)

        Setup: executor core=2 max=2 queue=2 policy=abort; read-timeout=0 (unbounded); the source
        holds every request open. Four GET /quotes requests (one ISIN each) were sent: two run on
        market-data workers, two wait in the queue. A fifth was sent after that.

        Fifth request: HTTP %d
        PoolStats: %s
        Requests that reached the source: %d (the queued two never did)

        """
            .formatted(refused.statusCode(), stats, source.arrived()));
    out.append("== market-data workers ==\n\n");
    for (ThreadInfo t : pool) {
      append(out, t);
    }
    out.append(
        "== Tomcat workers of the four admitted requests (waiting for their outcome) ==\n\n");
    for (ThreadInfo t : tomcat) {
      append(out, t);
    }
    long blocked = pool.stream().filter(t -> t.getThreadState() == Thread.State.BLOCKED).count();
    long inSend =
        pool.stream()
            .filter(
                t ->
                    Arrays.stream(t.getStackTrace())
                        .anyMatch(f -> f.getClassName().endsWith("RestMarketPriceSource")))
            .count();
    long[] deadlocked = threads.findDeadlockedThreads();
    out.append(
        """
        == Why this is a full pool and not a deadlock ==

        1. %d of %d market-data workers are inside RestMarketPriceSource.priceOf, all in the same
           wait frame (CompletableFuture.get under the JDK HttpClient's send). They are waiting on
           something outside the JVM: the source.
        2. %d market-data workers are BLOCKED. A deadlock is a cycle of workers each holding
           what another needs; a worker parked on a future holds no monitor at all.
        3. ThreadMXBean.findDeadlockedThreads(): %s
        4. PoolStats.queued == %d == the configured queue, active == %d == the configured max, and
           rejected == %d: the pool is at both limits and shedding, which is a capacity fact.
        5. Release the source and every worker finishes and the queue drains (the test asserts all
           four admitted requests then answer 200). A deadlock would not recover.
        """
            .formatted(
                inSend,
                pool.size(),
                blocked,
                deadlocked == null ? "null (no deadlock)" : Arrays.toString(deadlocked),
                stats.queued(),
                stats.active(),
                stats.rejected()));
    return out.toString();
  }

  private static void append(StringBuilder out, ThreadInfo t) {
    out.append("\"%s\" #%d %s%n".formatted(t.getThreadName(), t.getThreadId(), t.getThreadState()));
    if (t.getLockInfo() != null) {
      out.append("   - waiting on ").append(t.getLockInfo()).append('\n');
    }
    for (StackTraceElement frame : t.getStackTrace()) {
      out.append("        at ").append(frame).append('\n');
    }
    out.append('\n');
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
