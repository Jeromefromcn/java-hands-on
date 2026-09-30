package com.jerome.slledger.bootstrap.position;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.loan.OptimisticLoanPositionRepository;
import com.jerome.slledger.adapter.persistence.testing.SharedPostgres;
import com.jerome.slledger.application.position.OpenPosition;
import com.jerome.slledger.application.position.OpenPositionCommand;
import com.jerome.slledger.application.position.ReturnCommand;
import com.jerome.slledger.application.position.ReturnPosition;
import com.jerome.slledger.application.position.ReturnPositionOnce;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanPositionRepository;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.CounterpartyId;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exercise 5: two returns racing on one position, through the real use cases, and where the retry
 * has to sit for the loser to land.
 */
@Timeout(30)
class OptimisticReturnIT {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final LocalDate TRADE_DATE = LocalDate.of(2026, 9, 1);
  private static final StringBuilder EVIDENCE = new StringBuilder();

  private ConfigurableApplicationContext context;
  private BarrierAfterFind barrier;
  private JdbcClient jdbc;
  private LoanPositionId p1;
  private ExecutorService threads;

  @BeforeAll
  static void container() {
    SharedPostgres.start();
  }

  @AfterAll
  static void writeEvidence() {
    Evidence.write("06-optimistic-retry.txt", EVIDENCE.toString());
  }

  @BeforeEach
  void start() {
    context = LedgerApp.start(List.of(BarrierWiring.class, InsideTxRetryingReturn.class));
    barrier = context.getBean(BarrierAfterFind.class);
    jdbc = context.getBean(JdbcClient.class);
    LedgerApp.referenceData(jdbc);
    p1 =
        context
            .getBean(OpenPosition.class)
            .open(
                new OpenPositionCommand(
                    new IdempotencyKey("OPEN-P1"),
                    new CounterpartyId("CP-001"),
                    new Isin("HK0000000001"),
                    100,
                    Money.of("4126.00", HKD),
                    new FlatRate(Rate.of("0.0365")),
                    TRADE_DATE))
            .position();
    threads = Executors.newFixedThreadPool(2);
  }

  @AfterEach
  void stop() {
    threads.shutdownNow();
    context.close();
  }

  @Test
  void withoutRetryExactlyOneOfTwoConcurrentReturnsLoses() throws Exception {
    ReturnPositionOnce once = context.getBean(ReturnPositionOnce.class);
    barrier.arm(2);

    List<Throwable> outcomes =
        race(() -> once.apply(returnOf("R-10", 10)), () -> once.apply(returnOf("R-20", 20)));

    List<Throwable> failures = outcomes.stream().filter(o -> o != null).toList();
    assertThat(failures).hasSize(1);
    assertThat(failures.getFirst()).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    long outstanding = outstanding();
    assertThat(outstanding).isIn(90L, 80L);
    long winner = 100 - outstanding;
    assertThat(returnTrades()).containsExactly(winner);
    assertThat(version()).isEqualTo(1);
    EVIDENCE.append(
        "ReturnPositionOnce x2, both loaded at version 0%n  outcomes: %s%n  outstanding=%d version=%d trades=%d%n%n"
            .formatted(describe(outcomes), outstanding, version(), tradeCount()));
  }

  @Test
  void aRetryOutsideTheTransactionLandsBoth() throws Exception {
    ReturnPosition returns = context.getBean(ReturnPosition.class);
    barrier.arm(2);

    List<Throwable> outcomes =
        race(() -> returns.apply(returnOf("R-10", 10)), () -> returns.apply(returnOf("R-20", 20)));

    assertThat(outcomes).containsOnlyNulls();
    assertThat(outstanding()).isEqualTo(70);
    assertThat(version()).isEqualTo(2);
    assertThat(tradeCount()).isEqualTo(3);
    EVIDENCE.append(
        "ReturnPosition x2 (retry outside the transaction, max-attempts 3)%n  outcomes: %s%n  outstanding=%d version=%d trades=%d%n%n"
            .formatted(describe(outcomes), outstanding(), version(), tradeCount()));
  }

  /**
   * Retry inside one transaction. The first attempt loads P1 at version 0; another transaction
   * returns 20 and commits; the flush fails; the loop finds again. See
   * docs/decisions/06-persistence.md
   */
  @Test
  void reproducesRetryInsideTransactionSeesStaleEntity() throws Exception {
    InsideTxRetryingReturn inside = context.getBean(InsideTxRetryingReturn.class);
    ReturnPositionOnce once = context.getBean(ReturnPositionOnce.class);
    CountDownLatch loaded = new CountDownLatch(1);
    CountDownLatch otherCommitted = new CountDownLatch(1);

    List<InsideTxRetryingReturn.Attempt> attempts =
        java.util.Collections.synchronizedList(new ArrayList<>());
    Future<?> insideRun =
        threads.submit(
            () -> {
              inside.apply(p1, 10, 3, loaded, otherCommitted, attempts);
              return null;
            });
    assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
    once.apply(returnOf("R-20", 20));
    otherCommitted.countDown();

    Throwable commitFailure = null;
    try {
      insideRun.get(20, TimeUnit.SECONDS);
    } catch (java.util.concurrent.ExecutionException e) {
      commitFailure = e.getCause();
    }
    InsideTxRetryingReturn.Report report = new InsideTxRetryingReturn.Report(List.copyOf(attempts));

    // Every attempt reads the one managed entity, still at version 0, so every flush fails. What
    // each attempt "sees" is the entity's own uncommitted decrements, 100, 90, 80, never the 80
    // the other transaction committed: the third 80 is this loop's arithmetic, not a fresh read.
    assertThat(report.attempts()).hasSize(3);
    assertThat(report.attempts())
        .extracting(InsideTxRetryingReturn.Attempt::outstandingSeen)
        .containsExactly(100L, 90L, 80L);
    assertThat(report.attempts())
        .allSatisfy(
            a -> {
              assertThat(a.sameEntityInstance()).isTrue();
              assertThat(a.entityVersion()).isZero();
              // Raw JPA, not Spring's: flush() on the shared EntityManager is not behind a
              // @Repository proxy, so nothing translates it. A loop catching Spring's
              // OptimisticLockingFailureException would not even catch it.
              assertThat(a.failure())
                  .isInstanceOf(jakarta.persistence.OptimisticLockException.class)
                  .isNotInstanceOf(OptimisticLockingFailureException.class);
            });
    assertThat(commitFailure)
        .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
    assertThat(outstanding()).isEqualTo(80);
    assertThat(returnTrades()).containsExactly(20L);

    EVIDENCE.append(
        "Retry inside one @Transactional (InsideTxRetryingReturn), other tx returned 20%n"
            .formatted());
    for (int i = 0; i < report.attempts().size(); i++) {
      var a = report.attempts().get(i);
      EVIDENCE.append(
          "  attempt %d: outstanding seen=%d, same managed entity as attempt 1=%s, entity version=%d, failure=%s%n"
              .formatted(
                  i + 1,
                  a.outstandingSeen(),
                  a.sameEntityInstance(),
                  a.entityVersion(),
                  a.failure() == null
                      ? "none"
                      : a.failure().getClass().getName() + ": " + firstLine(a.failure())));
    }
    EVIDENCE.append(
        "  on return from the method: %s%n  row after: outstanding=%d version=%d trades=%d%n"
            .formatted(
                commitFailure == null
                    ? "committed"
                    : commitFailure.getClass().getName() + ": " + firstLine(commitFailure),
                outstanding(),
                version(),
                tradeCount()));
  }

  // --- helpers ---

  private ReturnCommand returnOf(String key, long quantity) {
    return new ReturnCommand(new IdempotencyKey(key), p1, quantity, TRADE_DATE);
  }

  /** Runs both at once and returns each one's failure, or null for a success, in order. */
  private List<Throwable> race(Runnable a, Runnable b) throws Exception {
    List<Future<?>> futures = List.of(threads.submit(a), threads.submit(b));
    List<Throwable> outcomes = new ArrayList<>();
    for (Future<?> future : futures) {
      try {
        future.get(20, TimeUnit.SECONDS);
        outcomes.add(null);
      } catch (java.util.concurrent.ExecutionException e) {
        outcomes.add(e.getCause());
      }
    }
    return outcomes;
  }

  private static String describe(List<Throwable> outcomes) {
    return outcomes.stream()
        .map(o -> o == null ? "ok" : o.getClass().getSimpleName())
        .toList()
        .toString();
  }

  private static String firstLine(Throwable t) {
    String message = String.valueOf(t.getMessage());
    return message.lines().findFirst().orElse("");
  }

  private long outstanding() {
    return jdbc.sql("SELECT outstanding_quantity FROM loan_position WHERE id = ?")
        .param(p1.value())
        .query(Long.class)
        .single();
  }

  private long version() {
    return jdbc.sql("SELECT version FROM loan_position WHERE id = ?")
        .param(p1.value())
        .query(Long.class)
        .single();
  }

  private long tradeCount() {
    return jdbc.sql("SELECT count(*) FROM loan_trade").query(Long.class).single();
  }

  private List<Long> returnTrades() {
    return jdbc.sql("SELECT quantity FROM loan_trade WHERE direction = 'RETURN' ORDER BY quantity")
        .query(Long.class)
        .list();
  }

  /** Test-only wiring; not a {@code @Configuration}, so only this test registers it. */
  static class BarrierWiring {

    @Bean
    @Primary
    BarrierAfterFind barrierAfterFind(OptimisticLoanPositionRepository real) {
      return new BarrierAfterFind(real);
    }
  }

  /**
   * Test-only. The retry stage 5 warned against: inside one transaction, catching the lock failure
   * at flush and trying again.
   */
  static class InsideTxRetryingReturn {

    record Attempt(
        long outstandingSeen, boolean sameEntityInstance, long entityVersion, Throwable failure) {}

    record Report(List<Attempt> attempts) {}

    private final LoanPositionRepository positions;
    private final EntityManager entityManager;

    InsideTxRetryingReturn(
        OptimisticLoanPositionRepository positions, EntityManager entityManager) {
      this.positions = positions;
      this.entityManager = entityManager;
    }

    /**
     * Attempts go into the caller's list, not a field: this bean is a proxy, and the caller needs
     * them even when the method's commit fails after it returned.
     */
    @Transactional
    public void apply(
        LoanPositionId id,
        long quantity,
        int maxAttempts,
        CountDownLatch loaded,
        CountDownLatch otherCommitted,
        List<Attempt> attempts)
        throws InterruptedException {
      Object firstEntity = null;
      for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        LoanPosition position;
        Object entity;
        try {
          position = positions.find(id).orElseThrow();
          entity = entityManager.find(entityClass(), id.value());
        } catch (RuntimeException e) {
          attempts.add(new Attempt(-1, false, -1, e));
          break;
        }
        if (firstEntity == null) {
          firstEntity = entity;
        }
        long seen = position.outstandingQuantity();
        long version = versionOf(entity);
        if (attempt == 1) {
          loaded.countDown();
          otherCommitted.await(10, TimeUnit.SECONDS);
        }
        try {
          position.returnQuantity(quantity);
          positions.add(position);
          entityManager.flush();
          attempts.add(new Attempt(seen, entity == firstEntity, version, null));
          break;
        } catch (RuntimeException e) {
          attempts.add(new Attempt(seen, entity == firstEntity, version, e));
        }
      }
    }

    private static Class<?> entityClass() {
      try {
        return Class.forName("com.jerome.slledger.adapter.persistence.loan.LoanPositionEntity");
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException(e);
      }
    }

    private static long versionOf(Object entity) {
      try {
        var field = entity.getClass().getDeclaredField("version");
        field.setAccessible(true);
        return field.getLong(entity);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
