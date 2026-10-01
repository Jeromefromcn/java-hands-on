package com.jerome.slledger.bootstrap.observability;

import com.jerome.slledger.application.batch.BatchRunMetrics;
import com.jerome.slledger.application.batch.RunSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * What a finished job execution reports, as meters: how long it took (by job and final status) and
 * how many items it read, wrote, filtered and skipped. When it last succeeded is not here: that is
 * read from the Batch metadata ({@link BatchMeters}), so it survives a restart of the process.
 */
public final class MicrometerBatchRunMetrics implements BatchRunMetrics {

  private final MeterRegistry registry;

  public MicrometerBatchRunMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void record(RunSummary summary) {
    Timer.builder("ledger.batch.run.duration")
        .description("Wall-clock time of one job execution")
        .tag("batch_job", summary.job())
        .tag("status", summary.status())
        .register(registry)
        .record(summary.duration());
    item(summary, "read", summary.read());
    item(summary, "written", summary.written());
    item(summary, "filtered", summary.filtered());
    item(summary, "skipped", summary.skipped());
  }

  private void item(RunSummary summary, String kind, long count) {
    registry
        .counter("ledger.batch.items", "batch_job", summary.job(), "kind", kind)
        .increment(count);
  }
}
