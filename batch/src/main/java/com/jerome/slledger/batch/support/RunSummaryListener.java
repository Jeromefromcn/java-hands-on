package com.jerome.slledger.batch.support;

import com.jerome.slledger.application.batch.BatchRunMetrics;
import com.jerome.slledger.application.batch.RunSummary;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;

/**
 * Reports what a job execution did to {@link BatchRunMetrics} when it ends, completed or failed.
 * Stage 12 binds that to Micrometer.
 *
 * <p>The counts are those of the top-level steps only. A partitioned step's manager already holds
 * the sum of its workers, so adding the workers' rows as well would count everything twice.
 */
public class RunSummaryListener implements JobExecutionListener {

  private static final Logger LOG = LoggerFactory.getLogger(RunSummaryListener.class);

  private final BatchRunMetrics metrics;

  public RunSummaryListener(BatchRunMetrics metrics) {
    this.metrics = metrics;
  }

  @Override
  public void afterJob(JobExecution execution) {
    try {
      metrics.record(summarise(execution));
    } catch (RuntimeException e) {
      // A metrics failure is not a reason to fail the job that was measured.
      LOG.warn("Could not record the summary of {}", execution.getJobInstance().getJobName(), e);
    }
  }

  static RunSummary summarise(JobExecution execution) {
    long read = 0;
    long written = 0;
    long filtered = 0;
    long skipped = 0;
    long commits = 0;
    long rollbacks = 0;
    for (StepExecution step : execution.getStepExecutions()) {
      if (step.getStepName().contains(":partition")) {
        continue;
      }
      read += step.getReadCount();
      written += step.getWriteCount();
      filtered += step.getFilterCount();
      skipped += step.getSkipCount();
      commits += step.getCommitCount();
      rollbacks += step.getRollbackCount();
    }
    Map<String, String> parameters = new LinkedHashMap<>();
    execution
        .getJobParameters()
        .forEach(parameter -> parameters.put(parameter.name(), String.valueOf(parameter.value())));
    LocalDateTime start = execution.getStartTime();
    LocalDateTime end =
        execution.getEndTime() == null ? LocalDateTime.now() : execution.getEndTime();
    return new RunSummary(
        execution.getJobInstance().getJobName(),
        parameters,
        execution.getStatus().name(),
        start == null ? java.time.Instant.EPOCH : start.atZone(ZoneId.systemDefault()).toInstant(),
        start == null ? Duration.ZERO : Duration.between(start, end),
        read,
        written,
        filtered,
        skipped,
        commits,
        rollbacks);
  }
}
