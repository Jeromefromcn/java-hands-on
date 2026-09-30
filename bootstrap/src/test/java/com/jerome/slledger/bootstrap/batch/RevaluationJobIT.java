package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.application.messaging.ApplyPriceUpdate;
import com.jerome.slledger.application.messaging.IntegrationEvent.MarginCallRaised;
import com.jerome.slledger.application.messaging.IntegrationEvent.PriceUpdated;
import com.jerome.slledger.application.messaging.PriceApplication;
import com.jerome.slledger.application.revaluation.RaiseMarginCalls;
import com.jerome.slledger.application.revaluation.RevaluationDispatcher;
import com.jerome.slledger.application.revaluation.RevaluationLauncher;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;

/**
 * The revaluation of a security with more open positions than the inline limit: the price update
 * defers, the dispatcher launches the job, and the job raises one margin call per shortfall, once.
 * Positions 1-30 are covered by collateral, 31-34 hold collateral in another currency (the domain
 * refuses to compare it), and the other 66 are short.
 */
@Timeout(300)
class RevaluationJobIT {

  private static final Isin ISIN = new Isin("HK0000000001");
  private static final Currency HKD = Currency.getInstance("HKD");
  private static final StringBuilder EVIDENCE =
      new StringBuilder(
          """
          Stage 11, section 10: the revaluation job. 100 open positions in HK0000000001 (price 41.26
          HKD, 1,000 shares each): 30 covered by 100,000 HKD of collateral, 4 with USD collateral,
          66 short by 40,260.00 HKD. Chunk size 20, inline limit 10. Real Postgres; the outbox rows
          are read from outbox_event (the relay is off).

          """);

  private static BatchLedger ledger;
  private static RevaluationLauncher launcher;
  private static RevaluationDispatcher dispatcher;

  @BeforeAll
  static void start() {
    ledger =
        BatchLedger.startWithOutbox(
            "--ledger.batch.chunk-size=20", "--ledger.revaluation.inline-limit=10");
    launcher = ledger.bean(RevaluationLauncher.class);
    dispatcher = ledger.bean(RevaluationDispatcher.class);
  }

  @AfterAll
  static void stop() {
    Evidence.write("11-revaluation-job.txt", EVIDENCE.toString());
    ledger.close();
  }

  @BeforeEach
  void fresh() {
    ledger.resetLedger();
    ledger.resetBatchMetadata();
    ledger.jdbc.sql("UPDATE security SET price_version = 0").update();
    ledger.seed(100);
    ledger
        .jdbc
        .sql("UPDATE loan_position SET collateral_amount = 100000.00 WHERE id <= 'P000030'")
        .update();
    ledger
        .jdbc
        .sql(
            "UPDATE loan_position SET collateral_currency = 'USD'"
                + " WHERE id > 'P000030' AND id <= 'P000034'")
        .update();
  }

  private long outboxCalls() {
    return ledger
        .jdbc
        .sql("SELECT count(*) FROM outbox_event WHERE type = 'MarginCallRaised'")
        .query(Long.class)
        .single();
  }

  @Test
  @DisplayName("the job raises one call per shortfall, skips and records the four it cannot value")
  void jobRaisesOneCallPerShortfall() {
    RevaluationLauncher.Result result = launcher.launch(ISIN, 1);

    assertThat(result).isEqualTo(RevaluationLauncher.Result.COMPLETED);
    assertThat(outboxCalls()).isEqualTo(66);
    assertThat(ledger.count("margin_call_marker")).isEqualTo(66);
    assertThat(
            ledger
                .jdbc
                .sql("SELECT item_key FROM batch_skip WHERE job = 'revaluation' ORDER BY item_key")
                .query(String.class)
                .list())
        .containsExactly("P000031", "P000032", "P000033", "P000034");
    long execution =
        ledger
            .jdbc
            .sql("SELECT max(job_execution_id) FROM batch_job_execution")
            .query(Long.class)
            .single();
    assertThat(
            ledger
                .jdbc
                .sql(
                    "SELECT read_count, write_count, filter_count, process_skip_count"
                        + " FROM batch_step_execution WHERE job_execution_id = ?")
                .param(execution)
                .query(
                    (rs, n) -> List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)))
                .single())
        .containsExactly(100L, 66L, 30L, 4L);
    String payload =
        ledger
            .jdbc
            .sql("SELECT payload FROM outbox_event WHERE type = 'MarginCallRaised' LIMIT 1")
            .query(String.class)
            .single();
    assertThat(payload).contains("\"40260.00\"").contains("HKD");
    EVIDENCE.append(
        """
        -- launch(HK0000000001, version 1)
        %s
        outbox MarginCallRaised rows: %d; margin_call_marker rows: %d; batch_skip rows: %d
        (read 100 = written 66 + filtered 30 + skipped 4)
        one payload: %s

        """
            .formatted(
                ledger.stepTable(execution),
                outboxCalls(),
                ledger.count("margin_call_marker"),
                ledger.count("batch_skip"),
                payload));
  }

  @Test
  @DisplayName("running every chunk again, in a second job instance, raises nothing twice")
  void aRerunRaisesNothingTwice() throws Exception {
    launcher.launch(ISIN, 1);
    long before = outboxCalls();

    JobOperator operator = ledger.bean(JobOperator.class);
    var rerun =
        operator.run(
            ledger.app.getBean("revaluation", Job.class),
            new JobParametersBuilder()
                .addString("isin", ISIN.value())
                .addLong("priceVersion", 1L)
                .addLong("rerun", 1L)
                .toJobParameters());

    assertThat(rerun.getStatus().name()).isEqualTo("COMPLETED");
    assertThat(outboxCalls()).isEqualTo(before).isEqualTo(66);
    assertThat(ledger.stepContextLong(rerun.getId(), "revalue", "revaluation.raised")).isZero();
    assertThat(ledger.stepContextLong(rerun.getId(), "revalue", "revaluation.alreadyRaised"))
        .isEqualTo(66L);
    assertThat(launcher.launch(ISIN, 1)).isEqualTo(RevaluationLauncher.Result.COMPLETED);
    EVIDENCE.append(
        """
        -- the same chunks run again (rerun=1): raised %d, already raised %d; outbox rows stay %d
        -- and launching (HK0000000001, 1) again is answered COMPLETED without a new execution

        """
            .formatted(
                ledger.stepContextLong(rerun.getId(), "revalue", "revaluation.raised"),
                ledger.stepContextLong(rerun.getId(), "revalue", "revaluation.alreadyRaised"),
                outboxCalls()));
  }

  private MarginCallRaised call(String position) {
    return new MarginCallRaised(
        UUID.randomUUID(),
        Instant.parse("2026-09-30T00:00:00Z"),
        new LoanPositionId(position),
        new CounterpartyId("CP-001"),
        ISIN,
        LocalDate.of(2026, 9, 30),
        Money.of("40260.00", HKD));
  }

  @Test
  @DisplayName(
      "a margin call is raised once per position and price version; a new version raises again")
  void markerIsPerPositionAndVersion() {
    RaiseMarginCalls raise = ledger.bean(RaiseMarginCalls.class);
    List<MarginCallRaised> calls = List.of(call("P000040"), call("P000041"));

    assertThat(raise.raise(calls, 7)).isEqualTo(2);
    assertThat(raise.raise(calls, 7)).isZero();
    assertThat(raise.raise(List.of(call("P000040"), call("P000042")), 7)).isEqualTo(1);
    assertThat(raise.raise(List.of(call("P000040"), call("P000041")), 8)).isEqualTo(2);
    assertThat(outboxCalls()).isEqualTo(5);
  }

  @Test
  @DisplayName("a request the price has moved past is superseded and its job is never launched")
  void overtakenRequestIsSuperseded() {
    ledger.jdbc.sql("UPDATE security SET price_version = 3 WHERE isin = 'HK0000000001'").update();
    ledger
        .jdbc
        .sql(
            "INSERT INTO revaluation_request (isin, price_version, status, created_at)"
                + " VALUES ('HK0000000001', 2, 'PENDING', now())")
        .update();

    int launched = dispatcher.dispatch(10);

    assertThat(launched).isZero();
    assertThat(
            ledger
                .jdbc
                .sql("SELECT status FROM revaluation_request WHERE price_version = 2")
                .query(String.class)
                .single())
        .isEqualTo("SUPERSEDED");
    assertThat(ledger.count("batch_job_instance")).isZero();
    assertThat(outboxCalls()).isZero();
  }

  @Test
  @DisplayName(
      "a price update above the inline limit defers; the dispatcher's job raises the calls")
  void updateAboveTheLimitDefersToTheBatch() {
    ApplyPriceUpdate apply = ledger.bean(ApplyPriceUpdate.class);

    PriceApplication result =
        apply.apply(
            new PriceUpdated(UUID.randomUUID(), Instant.now(), ISIN, Money.of("41.26", HKD), 5));

    assertThat(result.applied()).isTrue();
    assertThat(result.deferred()).isTrue();
    assertThat(outboxCalls()).isZero(); // nothing was revalued inside the update's transaction
    assertThat(
            ledger
                .jdbc
                .sql("SELECT status FROM revaluation_request WHERE price_version = 5")
                .query(String.class)
                .single())
        .isEqualTo("PENDING");

    assertThat(dispatcher.dispatch(10)).isEqualTo(1);

    assertThat(outboxCalls()).isEqualTo(66);
    assertThat(
            ledger
                .jdbc
                .sql("SELECT status FROM revaluation_request WHERE price_version = 5")
                .query(String.class)
                .single())
        .isEqualTo("DONE");
    assertThat(dispatcher.dispatch(10)).isZero(); // nothing left pending
    EVIDENCE.append(
        """
        -- ApplyPriceUpdate for version 5 with 100 open positions (limit 10)
        applied=%s deferred=%s; outbox rows right after the update: 0; revaluation_request: PENDING
        dispatcher.dispatch: launched 1; then outbox MarginCallRaised rows: %d; request: DONE
        """
            .formatted(result.applied(), result.deferred(), outboxCalls()));
  }

  @Test
  @DisplayName("a security at the limit is still revalued inside the update")
  void updateAtTheLimitStaysInline() {
    ledger.resetLedger();
    ledger.seed(10);
    ApplyPriceUpdate apply = ledger.bean(ApplyPriceUpdate.class);

    PriceApplication result =
        apply.apply(
            new PriceUpdated(UUID.randomUUID(), Instant.now(), ISIN, Money.of("41.26", HKD), 9));

    assertThat(result.deferred()).isFalse();
    assertThat(result.marginCalls()).isEqualTo(10);
    assertThat(outboxCalls()).isEqualTo(10);
    assertThat(ledger.count("revaluation_request")).isZero();
  }
}
