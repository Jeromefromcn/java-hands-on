package com.jerome.slledger.bootstrap.position;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.SharedPostgres;
import com.jerome.slledger.application.position.ReturnCommand;
import com.jerome.slledger.application.position.ReturnPosition;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanPositionId;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Exercise 6: the same returns through both lock strategies, in two shapes. Asserts correctness
 * only; durations and counts go to evidence. Each (strategy, shape) gets its own application and
 * database, one discarded warm-up run and three measured runs; the median by wall time is reported.
 */
@Tag("measurement")
class LockThroughputIT {

  private static final int THREADS = 8;
  private static final int MEASURED_RUNS = 3;
  private static final LocalDate TRADE_DATE = LocalDate.of(2026, 9, 1);
  private static final List<String> RESULTS = new ArrayList<>();

  @BeforeAll
  static void container() {
    SharedPostgres.start();
  }

  @AfterAll
  static void writeEvidence() {
    StringBuilder out = new StringBuilder();
    out.append(
        ("cores=%d, JDK %s, %d threads, ledger.return.max-attempts=5, pool=Hikari default (10)%n"
                + "one warm-up run discarded, then %d measured runs; the row is the median by wall"
                + " time%n")
            .formatted(
                Runtime.getRuntime().availableProcessors(),
                Runtime.version(),
                THREADS,
                MEASURED_RUNS));
    out.append(
        "%-12s %-7s %8s %8s %9s %9s %10s %9s | runs (wall ms)%n"
            .formatted(
                "locking", "shape", "wallMs", "ok", "qps", "optFails", "exhausted", "lockTmo"));
    RESULTS.forEach(line -> out.append(line).append(System.lineSeparator()));
    Evidence.write("06-lock-throughput.txt", out.toString());
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"optimistic, HOT", "optimistic, SPREAD", "pessimistic, HOT", "pessimistic, SPREAD"})
  @Timeout(600)
  void returnsLandExactlyOnceUnderEitherStrategy(String locking, Shape shape) throws Exception {
    try (ConfigurableApplicationContext context =
        LedgerApp.start(
            "--ledger.persistence.locking=" + locking,
            "--ledger.return.max-attempts=5",
            "--spring.jpa.properties.hibernate.generate_statistics=true")) {
      JdbcClient jdbc = context.getBean(JdbcClient.class);
      LedgerApp.referenceData(jdbc);
      ReturnPosition returns = context.getBean(ReturnPosition.class);
      Statistics statistics =
          context
              .getBean(EntityManagerFactory.class)
              .unwrap(SessionFactoryImplementor.class)
              .getStatistics();

      run(shape, "warmup", jdbc, returns, statistics);
      List<Run> measured = new ArrayList<>();
      for (int i = 1; i <= MEASURED_RUNS; i++) {
        measured.add(run(shape, "run" + i, jdbc, returns, statistics));
      }

      measured.sort(Comparator.comparingLong(Run::wallMillis));
      Run median = measured.get(MEASURED_RUNS / 2);
      synchronized (RESULTS) {
        RESULTS.add(
            "%-12s %-7s %8d %8d %9.1f %9d %10d %9d | %s"
                .formatted(
                    locking,
                    shape,
                    median.wallMillis,
                    median.successes,
                    median.successes * 1000.0 / median.wallMillis,
                    median.optimisticFailures,
                    median.exhausted,
                    median.lockTimeouts,
                    measured.stream().map(r -> Long.toString(r.wallMillis)).toList()));
      }
    }
  }

  enum Shape {
    /** One position of 10,000; 400 returns of 1. */
    HOT(1, 10_000, 400),
    /** 40 positions; 10 returns of 1 on each. */
    SPREAD(40, 1_000, 10);

    final int positions;
    final long quantity;
    final int returnsEach;

    Shape(int positions, long quantity, int returnsEach) {
      this.positions = positions;
      this.quantity = quantity;
      this.returnsEach = returnsEach;
    }
  }

  record Run(
      long wallMillis,
      long successes,
      long optimisticFailures,
      long exhausted,
      long lockTimeouts) {}

  private static Run run(
      Shape shape, String label, JdbcClient jdbc, ReturnPosition returns, Statistics statistics)
      throws Exception {
    String prefix = shape + "-" + label + "-";
    jdbc.sql(
            "INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                + " rule_type, flat_rate)"
                + " SELECT ? || lpad(n::text, 3, '0'), 'CP-001', 'HK0000000001', ?, ?, 'OPEN',"
                + " 1000.00, 'HKD', 'FLAT', 0.0365 FROM generate_series(1, ?) n")
        .params(prefix, shape.quantity, shape.quantity, shape.positions)
        .update();

    // Round-robin over positions, so under SPREAD neighbouring tasks hit different rows.
    List<LoanPositionId> tasks = new ArrayList<>();
    for (int r = 0; r < shape.returnsEach; r++) {
      for (int p = 1; p <= shape.positions; p++) {
        tasks.add(new LoanPositionId(prefix + "%03d".formatted(p)));
      }
    }

    Map<LoanPositionId, AtomicLong> landed = new ConcurrentHashMap<>();
    AtomicLong exhausted = new AtomicLong();
    AtomicLong lockTimeouts = new AtomicLong();
    CountDownLatch gate = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    statistics.clear();
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < tasks.size(); i++) {
        LoanPositionId id = tasks.get(i);
        IdempotencyKey key = new IdempotencyKey(prefix + "R" + i);
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    returns.apply(new ReturnCommand(key, id, 1, TRADE_DATE));
                    landed.computeIfAbsent(id, k -> new AtomicLong()).incrementAndGet();
                  } catch (OptimisticLockingFailureException e) {
                    exhausted.incrementAndGet();
                  } catch (PessimisticLockingFailureException e) {
                    lockTimeouts.incrementAndGet();
                  }
                  return null;
                }));
      }
      long started = System.nanoTime();
      gate.countDown();
      for (Future<?> future : futures) {
        future.get(300, TimeUnit.SECONDS);
      }
      long wallMillis = (System.nanoTime() - started) / 1_000_000;

      long successes = landed.values().stream().mapToLong(AtomicLong::get).sum();
      assertThat(successes + exhausted.get() + lockTimeouts.get()).isEqualTo(tasks.size());
      for (int p = 1; p <= shape.positions; p++) {
        LoanPositionId id = new LoanPositionId(prefix + "%03d".formatted(p));
        long expected = shape.quantity - landed.getOrDefault(id, new AtomicLong()).get();
        assertThat(
                jdbc.sql("SELECT outstanding_quantity FROM loan_position WHERE id = ?")
                    .param(id.value())
                    .query(Long.class)
                    .single())
            .as("outstanding of %s", id.value())
            .isEqualTo(expected);
      }
      assertThat(
              jdbc.sql("SELECT count(*) FROM loan_trade WHERE position_id LIKE ? || '%'")
                  .param(prefix)
                  .query(Long.class)
                  .single())
          .isEqualTo(successes);
      return new Run(
          wallMillis,
          successes,
          statistics.getOptimisticFailureCount(),
          exhausted.get(),
          lockTimeouts.get());
    } finally {
      pool.shutdownNow();
    }
  }
}
