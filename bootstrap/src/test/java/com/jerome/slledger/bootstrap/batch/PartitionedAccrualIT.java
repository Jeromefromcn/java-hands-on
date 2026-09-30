package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.batch.accrual.RunAccrual;
import com.jerome.slledger.batch.accrual.RunAccrual.Outcome;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Every partition scheme books the same entries. The partitions are disjoint and complete: their
 * read counts add up to the ledger, no position is read by two of them, and the summary the
 * listener reports counts the manager step once.
 */
@Timeout(600)
class PartitionedAccrualIT {

  private static final int POSITIONS = 2_000;

  private record Run(String scheme, int workers, long entries, long distinct, String table) {}

  private Run run(String scheme) {
    try (BatchLedger ledger =
        BatchLedger.start(
            "--ledger.batch.chunk-size=50",
            "--ledger.batch.partition-scheme=" + scheme,
            "--ledger.batch.partitions=4",
            "--ledger.batch.worker-threads=4")) {
      ledger.seedSkewed(POSITIONS, 70);
      Outcome outcome = ledger.bean(RunAccrual.class).run(BatchLedger.DAY);
      assertThat(outcome.status()).isEqualTo(RunAccrual.Status.COMPLETED);
      long workers =
          ledger
              .jdbc
              .sql(
                  "SELECT count(*) FROM batch_step_execution WHERE step_name LIKE 'accrualWorker:%'")
              .query(Long.class)
              .single();
      // Each worker's rows were read once: the sums of the workers equal the ledger.
      long workerReads =
          ledger
              .jdbc
              .sql(
                  "SELECT coalesce(sum(read_count), 0) FROM batch_step_execution"
                      + " WHERE step_name LIKE 'accrualWorker:%'")
              .query(Long.class)
              .single();
      if (workers > 0) {
        assertThat(workerReads).isEqualTo(POSITIONS);
        assertThat(ledger.workerContextSum(outcome.execution().getId(), "accrual.inserted"))
            .isEqualTo(POSITIONS);
      }
      return new Run(
          scheme,
          (int) workers,
          ledger.count("accrual_entry"),
          ledger
              .jdbc
              .sql("SELECT count(DISTINCT position_id) FROM accrual_entry")
              .query(Long.class)
              .single(),
          ledger.stepTable(outcome.execution().getId()));
    }
  }

  @Test
  @DisplayName("NONE, COUNTERPARTY and BALANCED book the same 2,000 entries; slices are disjoint")
  void everySchemeBooksTheSameEntries() {
    List<Run> runs = new ArrayList<>();
    for (String scheme : List.of("NONE", "COUNTERPARTY", "BALANCED")) {
      runs.add(run(scheme));
    }

    for (Run run : runs) {
      assertThat(run.entries()).as(run.scheme()).isEqualTo(POSITIONS);
      assertThat(run.distinct()).as(run.scheme()).isEqualTo(POSITIONS);
    }
    assertThat(runs.get(0).workers()).isZero();
    assertThat(runs.get(1).workers()).isEqualTo(8); // one per counterparty: CP-001..CP-008
    assertThat(runs.get(2).workers()).isEqualTo(4);
    StringBuilder text =
        new StringBuilder(
            """
            Stage 11, section 8: the same accrual (2,000 positions, 70%% of them CP-001, chunk
            size 50, four worker threads) under each partition scheme. Every scheme books 2,000
            distinct entries; the worker rows are the partitions.

            """);
    for (Run run : runs) {
      text.append(
          "-- %s (%d partitions)%n%s%n".formatted(run.scheme(), run.workers(), run.table()));
    }
    Evidence.write("11-partitioned-accrual.txt", text.toString());
  }
}
