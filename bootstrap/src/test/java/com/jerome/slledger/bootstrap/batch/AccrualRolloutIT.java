package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.application.accrual.DayCountRollout;
import com.jerome.slledger.application.rollout.Bucket;
import com.jerome.slledger.batch.accrual.RolloutSnapshotListener;
import com.jerome.slledger.batch.accrual.RunAccrual;
import com.jerome.slledger.batch.accrual.RunAccrual.Outcome;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The day-count rollout in the accrual job. 200 counterparties, each with one USD position (10.00 a
 * share, 1,000 shares, flat 3.65%: 365 a year, 1.01 a day on ACT/360 and 1.00 on ACT/365F) and one
 * HKD position (41.26 a share: 4.13 a day on either, HKD's convention being ACT/365F).
 *
 * <p>See docs/decisions/14-delivery.md.
 */
@Timeout(300)
class AccrualRolloutIT {

  private static final LocalDate NEXT_DAY = LocalDate.of(2026, 10, 2);
  private static final List<String> COUNTERPARTIES =
      IntStream.rangeClosed(1, 200).mapToObj(i -> "CP-R%03d".formatted(i)).toList();
  private static final StringBuilder EVIDENCE = new StringBuilder();

  private static BatchLedger ledger;
  private static RunAccrual accrual;

  @BeforeAll
  static void start() {
    ledger = BatchLedger.start(List.of(FailAt.class), "--ledger.batch.chunk-size=50");
    accrual = ledger.bean(RunAccrual.class);
    for (String counterparty : COUNTERPARTIES) {
      ledger
          .fixtures
          .counterparty(counterparty)
          .position("U-" + counterparty, counterparty, "US0000000001", 1_000)
          .position("H-" + counterparty, counterparty, "HK0000000001", 1_000);
    }
    ledger.fixtures.borrowTradesForAll("U-", BatchLedger.SETTLED);
    ledger.fixtures.borrowTradesForAll("H-", BatchLedger.SETTLED);
  }

  @AfterAll
  static void stop() {
    Evidence.write("14-accrual-rollout.txt", EVIDENCE.toString());
    ledger.close();
  }

  @BeforeEach
  void fresh() {
    FailAt.never();
    ledger.jdbc.sql("DELETE FROM accrual_entry").update();
    ledger.resetBatchMetadata();
    rolloutAt(0);
  }

  private static void rolloutAt(int percent) {
    ledger
        .jdbc
        .sql("UPDATE feature_rollout SET percent = ? WHERE flag = ?")
        .params(percent, DayCountRollout.FLAG)
        .update();
  }

  private static Set<String> selectedAt(int percent) {
    return COUNTERPARTIES.stream()
        .filter(id -> Bucket.selected(DayCountRollout.FLAG, new CounterpartyId(id), percent))
        .collect(Collectors.toSet());
  }

  /** Fee per position on a date, keyed by position id. */
  private static Map<String, BigDecimal> fees(LocalDate date) {
    Map<String, BigDecimal> fees = new TreeMap<>();
    ledger
        .jdbc
        .sql("SELECT position_id, fee_amount FROM accrual_entry WHERE business_date = ?")
        .param(date)
        .query((rs, n) -> fees.put(rs.getString(1), rs.getBigDecimal(2)))
        .list();
    return fees;
  }

  private static void assertBookedUnder(LocalDate date, Set<String> onRollout) {
    Map<String, BigDecimal> fees = fees(date);
    assertThat(fees).hasSize(400);
    for (String counterparty : COUNTERPARTIES) {
      BigDecimal usd = fees.get("U-" + counterparty);
      assertThat(usd)
          .as("USD position of %s", counterparty)
          .isEqualByComparingTo(onRollout.contains(counterparty) ? "1.01" : "1.00");
      assertThat(fees.get("H-" + counterparty)).isEqualByComparingTo("4.13");
    }
  }

  @Test
  void atFivePercentExactlyTheSelectedCounterpartiesUsdPositionsAccrueOnAct360() {
    rolloutAt(5);
    Outcome run = accrual.run(BatchLedger.DAY);

    assertThat(run.status()).isEqualTo(RunAccrual.Status.COMPLETED);
    Set<String> selected = selectedAt(5);
    assertThat(selected).isNotEmpty().hasSizeLessThan(30);
    assertBookedUnder(BatchLedger.DAY, selected);
    assertThat(run.execution().getExecutionContext().getInt(RolloutSnapshotListener.KEY))
        .isEqualTo(5);
    EVIDENCE.append(
        "rollout 5%%: %d of %d counterparties selected %s; their USD positions booked 1.01"
                .formatted(selected.size(), COUNTERPARTIES.size(), new TreeSet<>(selected))
            + " (ACT/360), every other USD position 1.00 (ACT/365F), every HKD position 4.13;"
            + " the run's context records percent=5\n");
  }

  @Test
  void aRestartKeepsThePercentageItsFirstAttemptTookAndTheNextDateTakesTheNewOne() {
    rolloutAt(5);
    FailAt.on(3);
    Outcome failed = accrual.run(BatchLedger.DAY);
    assertThat(failed.status()).isEqualTo(RunAccrual.Status.FAILED);
    long bookedBeforeFailure = ledger.count("accrual_entry");

    // Between the failure and the restart someone raises it to 100%; then the run is resumed.
    rolloutAt(100);
    FailAt.never();
    Outcome resumed = accrual.run(BatchLedger.DAY);

    assertThat(resumed.status()).isEqualTo(RunAccrual.Status.COMPLETED);
    assertThat(resumed.execution().getJobInstanceId())
        .isEqualTo(failed.execution().getJobInstanceId());
    assertThat(resumed.execution().getExecutionContext().getInt(RolloutSnapshotListener.KEY))
        .isEqualTo(5);
    assertBookedUnder(BatchLedger.DAY, selectedAt(5));

    // The kill switch: the next date's run takes the value in effect when it starts.
    rolloutAt(0);
    Outcome next = accrual.run(NEXT_DAY);
    assertThat(next.status()).isEqualTo(RunAccrual.Status.COMPLETED);
    assertBookedUnder(NEXT_DAY, Set.of());
    EVIDENCE.append(
        "restart: failed at chunk 3 with %d entries booked at 5%%; rollout set to 100%%; the"
                .formatted(bookedBeforeFailure)
            + " resumed execution kept percent=5 from its context and booked the rest at 5%;"
            + " rollout set to 0%: the next date booked every USD position 1.00\n");
  }
}
