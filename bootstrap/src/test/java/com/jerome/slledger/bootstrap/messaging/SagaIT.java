package com.jerome.slledger.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.messaging.testing.Evidence;
import com.jerome.slledger.application.position.FindBookedTrade;
import com.jerome.slledger.application.position.OpenPositionCommand;
import com.jerome.slledger.application.saga.FailurePoint;
import com.jerome.slledger.application.saga.OpenPositionSaga;
import com.jerome.slledger.application.saga.OpenPositionSteps;
import com.jerome.slledger.application.saga.SagaLog;
import com.jerome.slledger.application.saga.SagaRecovery;
import com.jerome.slledger.application.saga.SagaResult;
import com.jerome.slledger.application.saga.SagaResult.Status;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.error.InsufficientCreditException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanTrade;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * The open-position saga against the real ledger: each step made to fail, each place the process
 * could die made to die, and the state asserted after. The participants are the real ones (credit
 * reservations, TCC holds, the ledger's own {@code OpenPosition}); only the failures are injected.
 */
@Timeout(300)
class SagaIT {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final BigDecimal ACCOUNT = new BigDecimal("1000000.00");
  private static final StringBuilder EVIDENCE = new StringBuilder();

  private static MessagingLedger ledger;
  private static int sagaNumber;

  private OpenPositionSteps realSteps;
  private SagaLog log;
  private FindBookedTrade booked;

  /** The process died. An Error, so no {@code catch (RuntimeException)} in the saga sees it. */
  static final class SimulatedCrash extends Error {
    private static final long serialVersionUID = 1L;

    SimulatedCrash(String point) {
      super("crashed at " + point);
    }
  }

  /** Steps that fail on demand, delegating to the real ones otherwise. */
  private static final class FaultySteps implements OpenPositionSteps {
    private final OpenPositionSteps real;
    private final Map<String, Integer> failures = new HashMap<>();

    FaultySteps(OpenPositionSteps real) {
      this.real = real;
    }

    FaultySteps failing(String step, int times) {
      failures.put(step, times);
      return this;
    }

    private void maybeFail(String step) {
      int left = failures.getOrDefault(step, 0);
      if (left > 0) {
        failures.put(step, left - 1);
        throw new IllegalStateException(step + " failed (injected)");
      }
    }

    @Override
    public void reserveCredit(String sagaId, OpenPositionCommand command) {
      maybeFail("reserveCredit");
      real.reserveCredit(sagaId, command);
    }

    @Override
    public void freezeCollateral(String sagaId, OpenPositionCommand command) {
      maybeFail("freezeCollateral");
      real.freezeCollateral(sagaId, command);
    }

    @Override
    public LoanTrade createPosition(OpenPositionCommand command) {
      maybeFail("createPosition");
      return real.createPosition(command);
    }

    @Override
    public void complete(String sagaId) {
      maybeFail("complete");
      real.complete(sagaId);
    }

    @Override
    public void cancelHold(String sagaId) {
      maybeFail("cancelHold");
      real.cancelHold(sagaId);
    }

    @Override
    public void releaseReservation(String sagaId) {
      maybeFail("releaseReservation");
      real.releaseReservation(sagaId);
    }
  }

  @BeforeAll
  static void start() {
    ledger = MessagingLedger.start();
  }

  @AfterAll
  static void stop() {
    ledger.close();
    Evidence.write("10-saga.txt", EVIDENCE.toString());
  }

  @BeforeEach
  void setUp() {
    ledger.reset();
    ledger
        .jdbc
        .sql("UPDATE counterparty SET credit_limit_amount = 1000000000.00 WHERE id = 'CP-001'")
        .update();
    ledger
        .jdbc
        .sql("INSERT INTO collateral_account VALUES ('CP-001', 'HKD', ?, 0)")
        .param(ACCOUNT)
        .update();
    realSteps = ledger.app.getBean(OpenPositionSteps.class);
    log = ledger.app.getBean(SagaLog.class);
    booked = ledger.app.getBean(FindBookedTrade.class);
  }

  private static synchronized String nextId() {
    return "SAGA-" + (++sagaNumber);
  }

  private static OpenPositionCommand command(String id, String collateral) {
    return new OpenPositionCommand(
        new IdempotencyKey(id),
        new CounterpartyId("CP-001"),
        new Isin("HK0000000001"),
        1_000,
        Money.of(collateral, HKD),
        new FlatRate(Rate.of("0.0365")),
        LocalDate.of(2026, 9, 1));
  }

  private OpenPositionSaga saga(OpenPositionSteps steps, FailurePoint failures) {
    return new OpenPositionSaga(steps, log, booked, failures, 3);
  }

  private OpenPositionSaga saga(OpenPositionSteps steps) {
    return saga(steps, FailurePoint.NONE);
  }

  // ---- what the ledger looks like ---------------------------------------------------------

  private BigDecimal available() {
    return ledger
        .jdbc
        .sql("SELECT available FROM collateral_account")
        .query(BigDecimal.class)
        .single();
  }

  private BigDecimal frozen() {
    return ledger
        .jdbc
        .sql("SELECT frozen FROM collateral_account")
        .query(BigDecimal.class)
        .single();
  }

  private String hold(String id) {
    return ledger
        .jdbc
        .sql("SELECT status FROM tcc_transaction WHERE xid = ?")
        .param(id)
        .query(String.class)
        .optional()
        .orElse("none");
  }

  private String reservation(String id) {
    return ledger
        .jdbc
        .sql("SELECT status FROM credit_reservation WHERE saga_id = ?")
        .param(id)
        .query(String.class)
        .optional()
        .orElse("none");
  }

  private long positions() {
    return ledger.count("loan_position");
  }

  private long openedEvents() {
    return ledger
        .jdbc
        .sql("SELECT count(*) FROM outbox_event WHERE type = 'PositionOpened'")
        .query(Long.class)
        .single();
  }

  private String summary(String id) {
    return "position=%d outbox(PositionOpened)=%d hold=%s reservation=%s available=%s frozen=%s"
        .formatted(
            positions(),
            openedEvents(),
            hold(id),
            reservation(id),
            available().toPlainString(),
            frozen().toPlainString());
  }

  private void assertCompleted(String id) {
    assertThat(positions()).isEqualTo(1);
    assertThat(openedEvents()).isEqualTo(1);
    assertThat(hold(id)).isEqualTo("CONFIRMED");
    assertThat(reservation(id)).isEqualTo("CONSUMED");
    assertThat(available()).isEqualByComparingTo("950000.00");
    assertThat(frozen()).isEqualByComparingTo("0");
    assertThat(log.terminal(id)).contains(SagaLog.Phase.FORWARD);
  }

  private void assertCompensated(String id) {
    assertThat(positions()).isZero();
    assertThat(openedEvents()).isZero();
    assertThat(hold(id)).isIn("CANCELLED");
    assertThat(reservation(id)).isEqualTo("RELEASED");
    assertThat(available()).isEqualByComparingTo("1000000.00");
    assertThat(frozen()).isEqualByComparingTo("0");
    assertThat(log.terminal(id)).contains(SagaLog.Phase.COMPENSATE);
  }

  // ---- the paths --------------------------------------------------------------------------

  @Test
  @DisplayName(
      "happy path: position, event in the outbox, hold confirmed, reservation spent, log closed")
  void happyPath() {
    String id = nextId();

    SagaResult result = saga(realSteps).run(command(id, "50000.00"));

    assertThat(result.status()).isEqualTo(Status.COMPLETED);
    assertCompleted(id);
    assertThat(log.read(id))
        .extracting(SagaLog.Entry::step)
        .containsExactly(
            "saga",
            "reserveCredit",
            "reserveCredit",
            "freezeCollateral",
            "freezeCollateral",
            "createPosition",
            "createPosition",
            "complete",
            "complete",
            "saga");
    EVIDENCE
        .append("happy path: ")
        .append(summary(id))
        .append("; log: ")
        .append(log.read(id).size())
        .append(" entries, ends saga/FORWARD/DONE\n");
  }

  @Test
  @DisplayName("credit refused: nothing was done, and nothing is left to undo")
  void reservationFails() {
    String id = nextId();

    SagaResult result = saga(realSteps).run(command(id, "2000000000.00"));

    assertThat(result.status()).isEqualTo(Status.COMPENSATED);
    assertThat(result.failedStep()).isEqualTo("reserveCredit");
    assertThat(result.cause()).isInstanceOf(InsufficientCreditException.class);
    assertCompensated(id);
    EVIDENCE
        .append("fail at reserveCredit (real credit refusal): ")
        .append(summary(id))
        .append('\n');
  }

  @Test
  @DisplayName("collateral refused: the reservation is released")
  void freezeFails() {
    String id = nextId();
    ledger.jdbc.sql("UPDATE collateral_account SET available = 10000.00").update();

    SagaResult result = saga(realSteps).run(command(id, "50000.00"));

    assertThat(result.status()).isEqualTo(Status.COMPENSATED);
    assertThat(result.failedStep()).isEqualTo("freezeCollateral");
    assertThat(positions()).isZero();
    assertThat(reservation(id)).isEqualTo("RELEASED");
    assertThat(available()).isEqualByComparingTo("10000.00");
    assertThat(frozen()).isZero();
    EVIDENCE
        .append("fail at freezeCollateral (real: 10000.00 available, 50000.00 asked): ")
        .append(summary(id))
        .append('\n');
  }

  @Test
  @DisplayName("the position cannot be created: the hold is cancelled and the reservation released")
  void createFails() {
    String id = nextId();

    SagaResult result =
        saga(new FaultySteps(realSteps).failing("createPosition", 1)).run(command(id, "50000.00"));

    assertThat(result.status()).isEqualTo(Status.COMPENSATED);
    assertThat(result.failedStep()).isEqualTo("createPosition");
    assertCompensated(id);
    EVIDENCE.append("fail at createPosition (injected): ").append(summary(id)).append('\n');
  }

  @Test
  @DisplayName(
      "a confirm that fails after the pivot is retried and lands; the position is never undone")
  void confirmRetries() {
    String id = nextId();

    SagaResult result =
        saga(new FaultySteps(realSteps).failing("complete", 2)).run(command(id, "50000.00"));

    assertThat(result.status()).isEqualTo(Status.COMPLETED);
    assertCompleted(id);
    assertThat(
            log.read(id).stream()
                .filter(e -> e.step().equals("complete") && e.outcome() == SagaLog.Outcome.FAILED))
        .hasSize(2);
    EVIDENCE
        .append("fail at complete twice, then succeed (attempts 3): ")
        .append(summary(id))
        .append("; 2 FAILED entries in the log\n");
  }

  @Test
  @DisplayName(
      "a confirm that keeps failing leaves the position in place, the saga pending, and recovery finishes it")
  void confirmKeepsFailingThenRecovery() {
    String id = nextId();

    SagaResult result =
        saga(new FaultySteps(realSteps).failing("complete", 99)).run(command(id, "50000.00"));

    assertThat(result.status()).isEqualTo(Status.PENDING_COMPLETION);
    assertThat(positions()).isEqualTo(1);
    assertThat(hold(id)).isEqualTo("TRIED");
    assertThat(log.terminal(id)).isEmpty();
    String pending = summary(id);

    List<SagaResult> recovered = new SagaRecovery(log, saga(realSteps)).recoverAll();

    assertThat(recovered).extracting(SagaResult::status).containsExactly(Status.COMPLETED);
    assertCompleted(id);
    EVIDENCE
        .append("complete fails on every attempt: ")
        .append(pending)
        .append(" (pending); after recovery: ")
        .append(summary(id))
        .append('\n');
  }

  @Test
  @DisplayName(
      "a compensation that fails is logged, leaves the saga unfinished, and recovery finishes it")
  void compensationFailsThenRecovery() {
    String id = nextId();

    SagaResult result =
        saga(new FaultySteps(realSteps).failing("createPosition", 1).failing("cancelHold", 1))
            .run(command(id, "50000.00"));

    assertThat(result.status()).isEqualTo(Status.COMPENSATION_INCOMPLETE);
    assertThat(
            log.read(id).stream()
                .filter(
                    e -> e.outcome() == SagaLog.Outcome.FAILED && e.step().equals("cancelHold")))
        .singleElement()
        .satisfies(e -> assertThat(e.detail()).contains("cancelHold failed"));
    assertThat(hold(id)).isEqualTo("TRIED");
    assertThat(frozen()).isEqualByComparingTo("50000.00");
    assertThat(log.unfinished()).containsExactly(id);
    String stuck = summary(id);

    new SagaRecovery(log, saga(realSteps)).recoverAll();

    assertCompensated(id);
    assertThat(log.unfinished()).isEmpty();
    EVIDENCE
        .append("createPosition fails, then cancelHold fails once: ")
        .append(stuck)
        .append(" (incomplete, FAILED entry logged); after recovery: ")
        .append(summary(id))
        .append('\n');
  }

  @Test
  @DisplayName("running a finished saga again does nothing")
  void replayIsAnEcho() {
    String id = nextId();
    saga(realSteps).run(command(id, "50000.00"));

    SagaResult again = saga(realSteps).run(command(id, "50000.00"));

    assertThat(again.status()).isEqualTo(Status.COMPLETED);
    assertCompleted(id);
    assertThat(positions()).isEqualTo(1);
  }

  @Test
  @DisplayName("the log cannot be written inside a transaction")
  void logNeedsNoTransaction() {
    assertThatThrownBy(
            () ->
                ledger.transaction.executeWithoutResult(
                    status ->
                        log.record(
                            "X", "saga", SagaLog.Phase.FORWARD, SagaLog.Outcome.STARTED, null)))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  // ---- the process dies -------------------------------------------------------------------

  private record CrashCase(String point, String injectedFailure, boolean expectCompleted) {}

  static Stream<CrashCase> crashCases() {
    return Stream.of(
        new CrashCase("before:reserveCredit", null, false),
        new CrashCase("after:reserveCredit", null, false),
        new CrashCase("before:freezeCollateral", null, false),
        new CrashCase("after:freezeCollateral", null, false),
        new CrashCase("before:createPosition", null, false),
        new CrashCase("after:createPosition", null, true),
        new CrashCase("before:complete", null, true),
        new CrashCase("after:complete", null, true),
        // Already compensating (createPosition failed), and the process dies mid-compensation.
        new CrashCase("before:cancelHold", "createPosition", false),
        new CrashCase("after:cancelHold", "createPosition", false),
        new CrashCase("before:releaseReservation", "createPosition", false),
        new CrashCase("after:releaseReservation", "createPosition", false));
  }

  @ParameterizedTest(name = "crash at {0}")
  @MethodSource("crashCases")
  @DisplayName(
      "wherever the process dies, recovery leaves the ledger completed or compensated, never half")
  void crashThenRecover(CrashCase crash) {
    String id = nextId();
    FaultySteps faulty = new FaultySteps(realSteps);
    if (crash.injectedFailure() != null) {
      faulty.failing(crash.injectedFailure(), 1);
    }
    OpenPositionSteps steps = faulty;
    FailurePoint dies =
        point -> {
          if (point.equals(crash.point())) {
            throw new SimulatedCrash(point);
          }
        };

    assertThatThrownBy(() -> saga(steps, dies).run(command(id, "50000.00")))
        .isInstanceOf(SimulatedCrash.class);
    String atCrash = summary(id);
    assertThat(log.terminal(id)).isEmpty();

    new SagaRecovery(log, saga(realSteps)).recoverAll();

    if (crash.expectCompleted()) {
      assertCompleted(id);
    } else {
      assertCompensated(id);
    }
    EVIDENCE.append(
        "crash at %-27s | at the crash: %s%n   -> recovered: %s (%s)%n"
            .formatted(
                crash.point() + (crash.injectedFailure() == null ? "" : " (compensating)"),
                atCrash,
                summary(id),
                crash.expectCompleted() ? "rolled forward" : "compensated"));
  }

  // ---- two sagas, one limit ---------------------------------------------------------------

  @Test
  @DisplayName(
      "two sagas racing for the last of a credit limit: one wins, one is refused at the reservation")
  void racingForTheLastOfTheLimit() throws Exception {
    ledger
        .jdbc
        .sql("UPDATE counterparty SET credit_limit_amount = 100000.00 WHERE id = 'CP-001'")
        .update();
    String a = nextId();
    String b = nextId();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CyclicBarrier together = new CyclicBarrier(2);
      List<Future<SagaResult>> runs = new ArrayList<>();
      for (String id : List.of(a, b)) {
        runs.add(
            pool.submit(
                () -> {
                  together.await();
                  return saga(realSteps).run(command(id, "60000.00"));
                }));
      }
      List<SagaResult> results = new ArrayList<>();
      for (Future<SagaResult> run : runs) {
        results.add(run.get());
      }

      assertThat(results)
          .extracting(SagaResult::status)
          .containsExactlyInAnyOrder(Status.COMPLETED, Status.COMPENSATED);
      SagaResult refused =
          results.stream().filter(r -> r.status() == Status.COMPENSATED).findFirst().orElseThrow();
      assertThat(refused.failedStep()).isEqualTo("reserveCredit");
      assertThat(refused.cause()).isInstanceOf(InsufficientCreditException.class);
      assertThat(positions()).isEqualTo(1);
      EVIDENCE
          .append("two sagas of 60000.00 against a 100000.00 limit, started together: statuses=")
          .append(results.stream().map(r -> r.status().name()).sorted().toList())
          .append(", positions=")
          .append(positions())
          .append(", the refused one failed at ")
          .append(refused.failedStep())
          .append('\n');
    } finally {
      pool.shutdownNow();
    }
  }
}
