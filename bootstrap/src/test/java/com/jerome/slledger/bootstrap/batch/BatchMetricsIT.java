package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.batch.accrual.RunAccrual;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** What a finished accrual run leaves in the meters, and what a job that never ran reads. */
@Timeout(300)
class BatchMetricsIT {

  @Test
  @DisplayName("a completed run records its duration and items, and moves the last-success gauge")
  void runIsMeasured() {
    try (BatchLedger ledger = BatchLedger.start()) {
      ledger.seed(50);
      MeterRegistry registry = ledger.bean(MeterRegistry.class);
      Gauge accrual =
          registry
              .find("ledger.batch.last.success.timestamp.seconds")
              .tag("job", "dailyAccrual")
              .gauge();
      Gauge reconciliation =
          registry
              .find("ledger.batch.last.success.timestamp.seconds")
              .tag("job", "reconciliation")
              .gauge();
      assertThat(accrual.value()).as("never completed reads 0, not NaN").isZero();

      RunAccrual.Outcome outcome = ledger.bean(RunAccrual.class).run(BatchLedger.DAY);
      assertThat(outcome.status()).isEqualTo(RunAccrual.Status.COMPLETED);

      assertThat(
              registry
                  .find("ledger.batch.run.duration")
                  .tag("job", "dailyAccrual")
                  .tag("status", "COMPLETED")
                  .timer()
                  .count())
          .isEqualTo(1);
      assertThat(
              registry
                  .find("ledger.batch.items")
                  .tag("job", "dailyAccrual")
                  .tag("kind", "written")
                  .counter()
                  .count())
          .isEqualTo(50.0);
      // The gauge caches the database read for 5 s; the zero read above is still held.
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(15))
          .until(() -> accrual.value() > 0);
      long now = Instant.now().getEpochSecond();
      assertThat(accrual.value()).isBetween((double) now - 120, (double) now + 5);
      assertThat(reconciliation.value()).isZero();
      Evidence.write(
          "12-batch-meters.txt",
          "dailyAccrual last success timestamp seconds: "
              + accrual.value()
              + " (now "
              + now
              + "); reconciliation: "
              + reconciliation.value()
              + "\n");
    }
  }
}
