package com.jerome.slledger.application.batch;

/**
 * Where a finished job execution reports to. Stage 12 binds it to Micrometer; until then it is a
 * log line. It must not throw: a metrics failure is not a reason to fail the job that was measured.
 */
public interface BatchRunMetrics {

  void record(RunSummary summary);

  BatchRunMetrics NONE = summary -> {};
}
