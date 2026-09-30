package com.jerome.slledger.application.batch;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * What one job execution did, in terms a dashboard needs and no Spring Batch type appears in. The
 * counts are summed over the execution's steps (and, for a partitioned step, its partitions).
 *
 * <p>{@code skipped} is every item skipped on read, process or write; {@code written} does not
 * include them. {@code read} equals {@code written + filtered + skipped} for a run that completed.
 */
public record RunSummary(
    String job,
    Map<String, String> parameters,
    String status,
    Instant startedAt,
    Duration duration,
    long read,
    long written,
    long filtered,
    long skipped,
    long commits,
    long rollbacks) {

  public RunSummary {
    Objects.requireNonNull(job, "job");
    parameters = Map.copyOf(parameters);
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(duration, "duration");
  }
}
