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
 * The accrual of 20,000 positions at three chunk sizes, timed. A chunk is a transaction: the
 * smaller it is the more commits are paid, the larger it is the more a failure redoes and the
 * longer a transaction holds its connection and its locks. Chunk size 1 is run once as the
 * reference for "a commit per row", which is also what stage 5's per-row loop pays.
 *
 * <p>Measurement: not part of {@code verify}; run with {@code -Pmeasure}. Each run starts from an
 * empty accrual table and empty Batch metadata, the same date and the same data.
 */
@Tag("measurement")
@Timeout(1800)
class ChunkSizeMeasurementIT {

  private static final int POSITIONS = 20_000;
  private static final int RUNS = 3;

  private record Timing(int chunk, List<Long> millis, long commits) {

    long median() {
      List<Long> sorted = new ArrayList<>(millis);
      Collections.sort(sorted);
      return sorted.get(sorted.size() / 2);
    }
  }

  private Timing measure(int chunk, int runs) {
    try (BatchLedger ledger = BatchLedger.start("--ledger.batch.chunk-size=" + chunk)) {
      ledger.seed(POSITIONS);
      RunAccrual accrual = ledger.bean(RunAccrual.class);
      List<Long> millis = new ArrayList<>();
      long commits = 0;
      for (int run = 0; run < runs; run++) {
        ledger.jdbc.sql("DELETE FROM accrual_entry").update();
        ledger.resetBatchMetadata();
        long start = System.nanoTime();
        Outcome outcome = accrual.run(BatchLedger.DAY);
        millis.add((System.nanoTime() - start) / 1_000_000);
        assertThat(outcome.status()).isEqualTo(RunAccrual.Status.COMPLETED);
        assertThat(ledger.count("accrual_entry")).isEqualTo(POSITIONS);
        commits =
            ledger
                .jdbc
                .sql("SELECT commit_count FROM batch_step_execution WHERE job_execution_id = ?")
                .param(outcome.execution().getId())
                .query(Long.class)
                .single();
      }
      return new Timing(chunk, millis, commits);
    }
  }

  @Test
  void chunkSizes() {
    List<Timing> timings = new ArrayList<>();
    timings.add(measure(1, 1));
    for (int chunk : new int[] {10, 200, 5_000}) {
      timings.add(measure(chunk, RUNS));
    }
    StringBuilder text =
        new StringBuilder(
            """
            Stage 11, section 7: the accrual of %d active positions, one counterparty, one
            security, at four chunk sizes. Real Postgres 17 in a container on this machine, the
            application on JDK 21, reader page size = chunk size. Each run: empty accrual table,
            empty Batch metadata, the same date. Three runs per size (one for size 1), all shown,
            the median in the last column. Times are wall clock of RunAccrual.run, milliseconds.

            chunk size | commits | runs (ms)              | median (ms) | rows/s at the median
            """
                .formatted(POSITIONS));
    for (Timing timing : timings) {
      text.append(
          "%10d | %7d | %-22s | %11d | %d%n"
              .formatted(
                  timing.chunk(),
                  timing.commits(),
                  timing.millis(),
                  timing.median(),
                  POSITIONS * 1000L / Math.max(1, timing.median())));
    }
    Evidence.write("11-chunk-size.txt", text.toString());
    System.out.println(text);
  }
}
