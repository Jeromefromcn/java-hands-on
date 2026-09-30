package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.batch.accrual.RunAccrual;
import com.jerome.slledger.batch.accrual.RunDailyAccrual;
import com.jerome.slledger.batch.reconciliation.RunReconciliation;
import com.jerome.slledger.batch.retention.RunRetentionJob;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Runs the job named by {@code ledger.batch.command.job} once at start-up. The accrual is launched
 * through the lock when there is one, so a cron entry on every instance is safe.
 */
class BatchCommandRunner implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(BatchCommandRunner.class);

  private final BatchCommandProperties command;
  private final Clock clock;
  private final ObjectProvider<RunDailyAccrual> fencedAccrual;
  private final RunAccrual accrual;
  private final RunReconciliation reconciliation;
  private final RunRetentionJob retention;
  private final ConfigurableApplicationContext context;

  BatchCommandRunner(
      BatchCommandProperties command,
      Clock clock,
      ObjectProvider<RunDailyAccrual> fencedAccrual,
      RunAccrual accrual,
      RunReconciliation reconciliation,
      RunRetentionJob retention,
      ConfigurableApplicationContext context) {
    this.command = command;
    this.clock = clock;
    this.fencedAccrual = fencedAccrual;
    this.accrual = accrual;
    this.reconciliation = reconciliation;
    this.retention = retention;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    LocalDate date =
        command.date() != null
            ? command.date()
            : LocalDate.now(clock.withZone(BatchSchedulers.BUSINESS_ZONE));
    OptionalLong rerun =
        command.rerun() == null ? OptionalLong.empty() : OptionalLong.of(command.rerun());
    boolean succeeded;
    switch (command.job()) {
      case "accrual" -> {
        RunDailyAccrual fenced = fencedAccrual.getIfAvailable();
        RunAccrual.Outcome outcome =
            fenced != null && rerun.isEmpty()
                ? fenced.run(date)
                : accrual.run(
                    date,
                    rerun,
                    new org.springframework.batch.core.job.parameters.JobParametersBuilder());
        LOG.info("accrual {}: {}", date, outcome.status());
        succeeded =
            outcome.status() == RunAccrual.Status.COMPLETED
                || outcome.status() == RunAccrual.Status.ALREADY_DONE;
      }
      case "reconciliation" -> {
        if (command.snapshotId() == null || command.snapshotFile() == null) {
          throw new IllegalArgumentException(
              "reconciliation needs ledger.batch.command.snapshot-id and snapshot-file");
        }
        RunReconciliation.Outcome outcome =
            reconciliation.run(date, command.snapshotId(), Path.of(command.snapshotFile()), rerun);
        LOG.info("reconciliation {} {}: {}", date, command.snapshotId(), outcome.status());
        succeeded =
            outcome.status() == RunReconciliation.Status.COMPLETED
                || outcome.status() == RunReconciliation.Status.ALREADY_DONE;
      }
      case "retention" -> {
        RunRetentionJob.Outcome outcome = retention.run(date);
        LOG.info("retention {}: {}", date, outcome.status());
        succeeded =
            outcome.status() == RunRetentionJob.Status.COMPLETED
                || outcome.status() == RunRetentionJob.Status.ALREADY_DONE;
      }
      default ->
          throw new IllegalArgumentException(
              "ledger.batch.command.job must be accrual, reconciliation or retention, was '%s'"
                  .formatted(command.job()));
    }
    if (command.exitAfterRun()) {
      int code = succeeded ? 0 : 1;
      System.exit(SpringApplication.exit(context, () -> code));
    }
  }
}
