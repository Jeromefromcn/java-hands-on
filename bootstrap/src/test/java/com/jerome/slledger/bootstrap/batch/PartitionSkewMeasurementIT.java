package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.batch.accrual.RunAccrual;
import com.jerome.slledger.batch.accrual.RunAccrual.Outcome;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The accrual of 60,000 positions, 70% of them one counterparty, under three schemes: no
 * partitioning, one partition per counterparty (eight, one of them holding 42,000 rows) and eight
 * balanced partitions of 7,500. The step ends when its slowest partition ends, so the interesting
 * number is not the mean partition but the longest.
 *
 * <p>Measurement: run with {@code -Pmeasure}.
 */
@Tag("measurement")
@Timeout(3600)
class PartitionSkewMeasurementIT {

  private static final int POSITIONS = 60_000;
  private static final int RUNS = 3;

  private record Sample(
      long stepMillis,
      List<Long> allRuns,
      List<Long> partitionMillis,
      List<Long> partitionRows,
      List<String> timeline) {}

  private Sample sample(String scheme) {
    try (BatchLedger ledger =
        BatchLedger.start(
            "--ledger.batch.chunk-size=500",
            "--ledger.batch.partition-scheme=" + scheme,
            "--ledger.batch.partitions=8",
            "--ledger.batch.worker-threads=4")) {
      ledger.seedSkewed(POSITIONS, 70);
      RunAccrual accrual = ledger.bean(RunAccrual.class);
      List<Long> steps = new ArrayList<>();
      Sample last = null;
      for (int run = 0; run < RUNS; run++) {
        ledger.jdbc.sql("DELETE FROM accrual_entry").update();
        ledger.resetBatchMetadata();
        long start = System.nanoTime();
        Outcome outcome = accrual.run(BatchLedger.DAY);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        assertThat(outcome.status()).isEqualTo(RunAccrual.Status.COMPLETED);
        assertThat(ledger.count("accrual_entry")).isEqualTo(POSITIONS);
        List<Long> millis = new ArrayList<>();
        List<Long> rows = new ArrayList<>();
        List<String> timeline = new ArrayList<>();
        ledger
            .jdbc
            .sql(
                "SELECT s.read_count, extract(epoch FROM (s.end_time - s.start_time)) * 1000,"
                    + " extract(epoch FROM (s.start_time - m.start_time)) * 1000,"
                    + " extract(epoch FROM (s.end_time - m.start_time)) * 1000, s.step_name"
                    + " FROM batch_step_execution s JOIN batch_step_execution m"
                    + " ON m.job_execution_id = s.job_execution_id AND m.step_name = 'accrual'"
                    + " WHERE s.step_name LIKE 'accrualWorker:%' ORDER BY s.step_name")
            .query(
                (rs, n) -> {
                  rows.add(rs.getLong(1));
                  millis.add(Math.round(rs.getDouble(2)));
                  timeline.add(
                      "%s %d..%d"
                          .formatted(
                              rs.getString(5).substring(rs.getString(5).indexOf(':') + 1),
                              Math.round(rs.getDouble(3)),
                              Math.round(rs.getDouble(4))));
                  return null;
                })
            .list();
        steps.add(elapsed);
        last = new Sample(elapsed, List.of(), millis, rows, timeline);
      }
      List<Long> runs = new ArrayList<>(steps);
      Collections.sort(steps);
      return new Sample(
          steps.get(steps.size() / 2),
          runs,
          last.partitionMillis(),
          last.partitionRows(),
          last.timeline());
    }
  }

  @Test
  void partitionTail() {
    StringBuilder text =
        new StringBuilder(
            """
            Stage 11, section 8: the accrual of %d active positions, 70%% of them CP-001 and the
            rest spread over CP-002..CP-008, chunk size 500, four worker threads, Postgres 17 in
            a container on this machine. Median of %d runs of the whole step (wall clock of
            RunAccrual.run); the partition rows and times are from the last run.

            """
                .formatted(POSITIONS, RUNS));
    for (String scheme : List.of("NONE", "COUNTERPARTY", "BALANCED")) {
      Sample sample = sample(scheme);
      long longest = sample.partitionMillis().stream().mapToLong(Long::longValue).max().orElse(0);
      double mean =
          sample.partitionMillis().stream().mapToLong(Long::longValue).average().orElse(0);
      text.append(
          "%s: step median %d ms of runs %s; %d partitions; rows per partition %s; ms per partition %s;"
                  .formatted(
                      scheme,
                      sample.stepMillis(),
                      sample.allRuns(),
                      sample.partitionMillis().size(),
                      sample.partitionRows(),
                      sample.partitionMillis())
              + (sample.partitionMillis().isEmpty()
                  ? "%n%n".formatted()
                  : " longest %d ms, mean %.0f ms, longest/mean %.1f%n%n"
                          .formatted(longest, mean, longest / Math.max(1.0, mean))
                      + "  start..end offset in the step (ms): %s%n%n"
                          .formatted(sample.timeline())));
    }
    Evidence.write("11-partition-skew.txt", text.toString());
    System.out.println(text);
  }
}
