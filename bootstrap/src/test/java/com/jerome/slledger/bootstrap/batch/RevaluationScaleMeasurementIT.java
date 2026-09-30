package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.application.event.DomainEvents;
import com.jerome.slledger.application.messaging.ApplyPriceUpdate;
import com.jerome.slledger.application.messaging.IntegrationEvent.PriceUpdated;
import com.jerome.slledger.application.messaging.Outbox;
import com.jerome.slledger.application.messaging.SecurityPositions;
import com.jerome.slledger.application.messaging.SecurityPrices;
import com.jerome.slledger.application.revaluation.RevaluationLauncher;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Revaluing a security of 50,000 open positions three ways: only the inline path's single read of
 * every position (the part that cannot scale), the whole inline update (read, decide, and 50,000
 * outbox rows in one transaction, holding the security's row lock throughout), and the job in
 * chunks of 500.
 *
 * <p>Live heap is measured by forcing a collection and reading the used heap: once before the work
 * (the baseline) and then every 250 ms of the run from a sampling thread, keeping the peak. Forcing
 * collections slows what it samples, so the elapsed times are taken in runs without the sampler.
 */
@Tag("measurement")
@Timeout(3600)
class RevaluationScaleMeasurementIT {

  private static final int POSITIONS = 50_000;
  private static final Isin ISIN = new Isin("HK0000000001");

  private static long usedAfterGc() {
    System.gc();
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  private record Measured(long millis, long peakLiveKiB) {}

  private static Measured timed(Runnable work) {
    long start = System.nanoTime();
    work.run();
    return new Measured((System.nanoTime() - start) / 1_000_000, 0);
  }

  private static Measured peakLive(Runnable work) throws InterruptedException {
    long baseline = usedAfterGc();
    AtomicLong peak = new AtomicLong();
    Thread sampler =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    while (!Thread.currentThread().isInterrupted()) {
                      Thread.sleep(250);
                      peak.accumulateAndGet(usedAfterGc() - baseline, Math::max);
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                });
    work.run();
    sampler.interrupt();
    sampler.join();
    return new Measured(0, peak.get() / 1024);
  }

  private void reload(BatchLedger ledger) {
    ledger.resetLedger();
    ledger.resetBatchMetadata();
    ledger.jdbc.sql("UPDATE security SET price_version = 0").update();
    ledger.seed(POSITIONS);
  }

  @Test
  void inlineAgainstTheJob() throws Exception {
    try (BatchLedger ledger = BatchLedger.startWithOutbox("--ledger.batch.chunk-size=500")) {
      TransactionTemplate transaction =
          new TransactionTemplate(ledger.bean(PlatformTransactionManager.class));
      SecurityPositions positions = ledger.bean(SecurityPositions.class);
      Supplier<ApplyPriceUpdate> inline =
          () ->
              new ApplyPriceUpdate(
                  ledger.bean(SecurityPrices.class),
                  positions,
                  ledger.bean(Outbox.class),
                  ledger.bean(DomainEvents.class),
                  Clock.systemUTC());
      PriceUpdated update =
          new PriceUpdated(
              UUID.randomUUID(),
              Instant.now(),
              ISIN,
              Money.of("41.26", Currency.getInstance("HKD")),
              1);

      // 1. Only the read that cannot scale: every open position, mapped, in one transaction.
      reload(ledger);
      long baseline = usedAfterGc();
      long readStart = System.nanoTime();
      List<LoanPosition> all = transaction.execute(status -> positions.openIn(ISIN));
      long readMillis = (System.nanoTime() - readStart) / 1_000_000;
      long heldKiB = (usedAfterGc() - baseline) / 1024;
      assertThat(all).hasSize(POSITIONS);
      all = null;

      // 2. The whole inline update, timed, then again for its peak live heap.
      reload(ledger);
      Measured inlineTime =
          timed(() -> transaction.executeWithoutResult(s -> inline.get().apply(update)));
      assertThat(ledger.count("outbox_event")).isEqualTo(POSITIONS);
      reload(ledger);
      Measured inlineHeap =
          peakLive(() -> transaction.executeWithoutResult(s -> inline.get().apply(update)));

      // 3. The job, timed, then again for its peak live heap.
      RevaluationLauncher launcher = ledger.bean(RevaluationLauncher.class);
      reload(ledger);
      Measured jobTime =
          timed(
              () ->
                  assertThat(launcher.launch(ISIN, 1))
                      .isEqualTo(RevaluationLauncher.Result.COMPLETED));
      assertThat(ledger.count("outbox_event")).isEqualTo(POSITIONS);
      reload(ledger);
      Measured jobHeap = peakLive(() -> launcher.launch(ISIN, 1));

      String text =
          """
          Stage 11, section 10: revaluing one security with %d open positions, each 1,000 shares of a
          41.26 HKD security, collateral 1,000 HKD (every position is short, so every one raises a
          margin call and an outbox row). Postgres 17 in a container on this machine, JDK 21, default
          heap. Elapsed time and peak live heap come from separate runs (forcing collections to read
          the live heap slows the run being sampled).

          inline, the read alone (positions.openIn in one transaction) : %d ms; the list, held :
              %d KiB (%d bytes per position)
          inline, the whole update (read, decide, %d outbox rows, one transaction, the security's
              row locked throughout)                                    : %d ms; peak live heap
              %d KiB
          job, chunks of 500 (read, decide, %d outbox rows and %d markers, one transaction per
              chunk, no lock held across chunks)                       : %d ms; peak live heap
              %d KiB
          """
              .formatted(
                  POSITIONS,
                  readMillis,
                  heldKiB,
                  heldKiB * 1024 / POSITIONS,
                  POSITIONS,
                  inlineTime.millis(),
                  inlineHeap.peakLiveKiB(),
                  POSITIONS,
                  POSITIONS,
                  jobTime.millis(),
                  jobHeap.peakLiveKiB());
      Evidence.write("11-revaluation-scale.txt", text);
      System.out.println(text);
    }
  }
}
