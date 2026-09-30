package com.jerome.slledger.bootstrap.batch;

import com.jerome.slledger.adapter.persistence.testing.LedgerFixtures;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The real application with batch on, against a real Postgres, with the reference data loaded and
 * helpers to fill the ledger with synthetic positions through SQL (building them through {@code
 * OpenPosition} would time the setup, not the job).
 */
final class BatchLedger implements AutoCloseable {

  /** Every synthetic position settled on or before this date, so it is active from then on. */
  static final LocalDate SETTLED = LocalDate.of(2026, 9, 1);

  static final LocalDate DAY = LocalDate.of(2026, 9, 30);

  final ConfigurableApplicationContext app;
  final JdbcClient jdbc;
  final LedgerFixtures fixtures;

  private BatchLedger(ConfigurableApplicationContext app) {
    this.app = app;
    this.jdbc = app.getBean(JdbcClient.class);
    this.fixtures = new LedgerFixtures(jdbc);
  }

  static BatchLedger start(String... extraArgs) {
    return start(List.of(), extraArgs);
  }

  static BatchLedger start(List<Class<?>> extraSources, String... extraArgs) {
    // A property given twice on the command line is a list, not an override: later arguments have
    // to
    // replace earlier ones here, by key.
    Map<String, String> arguments = new LinkedHashMap<>();
    arguments.put("--ledger.batch.enabled", "true");
    for (String argument : extraArgs) {
      int equals = argument.indexOf('=');
      arguments.put(argument.substring(0, equals), argument.substring(equals + 1));
    }
    List<String> args = new ArrayList<>();
    arguments.forEach((key, value) -> args.add(key + "=" + value));
    BatchLedger ledger =
        new BatchLedger(LedgerApp.start(extraSources, args.toArray(String[]::new)));
    LedgerApp.referenceData(ledger.jdbc);
    return ledger;
  }

  /**
   * A second application instance on the same database as {@code first} (and whatever Redis the
   * arguments name): its own context, connection pool, job repository and lock client, sharing the
   * data. Not a second JVM: two contexts in one JVM share static state, which the tests avoid.
   */
  static BatchLedger startSharing(
      BatchLedger first, List<Class<?>> extraSources, String... extraArgs) {
    org.springframework.core.env.Environment environment = first.app.getEnvironment();
    Map<String, String> arguments = new LinkedHashMap<>();
    arguments.put("--spring.datasource.url", environment.getProperty("spring.datasource.url"));
    arguments.put(
        "--spring.datasource.username", environment.getProperty("spring.datasource.username"));
    arguments.put(
        "--spring.datasource.password", environment.getProperty("spring.datasource.password"));
    arguments.put("--ledger.batch.enabled", "true");
    for (String argument : extraArgs) {
      int equals = argument.indexOf('=');
      arguments.put(argument.substring(0, equals), argument.substring(equals + 1));
    }
    List<String> args = new ArrayList<>();
    arguments.forEach((key, value) -> args.add(key + "=" + value));
    List<Class<?>> sources = new ArrayList<>();
    sources.add(com.jerome.slledger.bootstrap.SlLedgerApplication.class);
    sources.addAll(extraSources);
    return new BatchLedger(
        new org.springframework.boot.builder.SpringApplicationBuilder(
                sources.toArray(Class<?>[]::new))
            .web(org.springframework.boot.WebApplicationType.NONE)
            .bannerMode(org.springframework.boot.Banner.Mode.OFF)
            .run(args.toArray(String[]::new)));
  }

  <T> T bean(Class<T> type) {
    return app.getBean(type);
  }

  /** {@code count} active positions {@code P000001…} of one counterparty, in HK0000000001. */
  BatchLedger seed(int count) {
    fixtures.bulkPositions("P", count, "HK0000000001", "'CP-001'").borrowTradesForAll("P", SETTLED);
    analyse();
    return this;
  }

  /**
   * {@code count} active positions where CP-001 holds {@code bigSharePercent} percent and the rest
   * are spread evenly over CP-002 to CP-008, so a partition per counterparty is badly uneven.
   */
  BatchLedger seedSkewed(int count, int bigSharePercent) {
    for (int i = 3; i <= 8; i++) {
      fixtures.counterparty("CP-00" + i);
    }
    fixtures
        .bulkPositions(
            "P",
            count,
            "HK0000000001",
            "CASE WHEN n % 100 < "
                + bigSharePercent
                + " THEN 'CP-001' ELSE 'CP-00' || (2 + n % 7) END")
        .borrowTradesForAll("P", SETTLED);
    analyse();
    return this;
  }

  /**
   * Statistics, as a real ledger has them. Right after a bulk insert a table has none until
   * autovacuum's analyse runs, up to a minute later, and the planner then guesses: with the guess,
   * a page of the counterparty-sliced reader took four times as long (see
   * docs/evidence/11-partition-skew-no-statistics.txt).
   */
  private void analyse() {
    jdbc.sql("ANALYZE").update();
  }

  /**
   * The sum of a number in the execution contexts of a step's partitions (or of the step itself).
   */
  long workerContextSum(long jobExecutionId, String key) {
    return bean(org.springframework.batch.core.repository.JobRepository.class)
        .getJobExecution(jobExecutionId)
        .getStepExecutions()
        .stream()
        .filter(step -> step.getExecutionContext().containsKey(key))
        .mapToLong(step -> step.getExecutionContext().getLong(key))
        .sum();
  }

  long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  /** Empties what a run writes, keeping reference data and the Batch metadata. */
  void resetLedger() {
    jdbc.sql("DELETE FROM accrual_entry").update();
    jdbc.sql("DELETE FROM batch_skip").update();
    jdbc.sql("DELETE FROM loan_trade").update();
    jdbc.sql("DELETE FROM loan_position").update();
  }

  /** The execution context a step saved, read through the job repository. */
  org.springframework.batch.infrastructure.item.ExecutionContext stepContext(
      long jobExecutionId, String stepName) {
    return bean(org.springframework.batch.core.repository.JobRepository.class)
        .getJobExecution(jobExecutionId)
        .getStepExecutions()
        .stream()
        .filter(step -> step.getStepName().equals(stepName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no step " + stepName + " in " + jobExecutionId))
        .getExecutionContext();
  }

  /** A number saved in a step's execution context. */
  long stepContextLong(long jobExecutionId, String stepName, String key) {
    return stepContext(jobExecutionId, stepName).getLong(key);
  }

  /** Empties the Batch metadata, so a test starts with no job instance. */
  void resetBatchMetadata() {
    jdbc.sql("DELETE FROM batch_step_execution_context").update();
    jdbc.sql("DELETE FROM batch_step_execution").update();
    jdbc.sql("DELETE FROM batch_job_execution_context").update();
    jdbc.sql("DELETE FROM batch_job_execution_params").update();
    jdbc.sql("DELETE FROM batch_job_execution").update();
    jdbc.sql("DELETE FROM batch_job_instance").update();
  }

  /** The Batch step rows of one job execution, one line per step, for evidence. */
  String stepTable(long jobExecutionId) {
    StringBuilder table = new StringBuilder();
    table.append(
        "step_name | status | read | write | commit | rollback | read_skip | process_skip |"
            + " write_skip | exit_code\n");
    jdbc.sql(
            "SELECT step_name, status, read_count, write_count, commit_count, rollback_count,"
                + " read_skip_count, process_skip_count, write_skip_count, exit_code"
                + " FROM batch_step_execution WHERE job_execution_id = ? ORDER BY step_execution_id")
        .param(jobExecutionId)
        .query(
            (rs, row) -> {
              table
                  .append(rs.getString(1))
                  .append(" | ")
                  .append(rs.getString(2))
                  .append(" | ")
                  .append(rs.getLong(3))
                  .append(" | ")
                  .append(rs.getLong(4))
                  .append(" | ")
                  .append(rs.getLong(5))
                  .append(" | ")
                  .append(rs.getLong(6))
                  .append(" | ")
                  .append(rs.getLong(7))
                  .append(" | ")
                  .append(rs.getLong(8))
                  .append(" | ")
                  .append(rs.getLong(9))
                  .append(" | ")
                  .append(rs.getString(10))
                  .append('\n');
              return null;
            })
        .list();
    return table.toString();
  }

  /** {@link #stepTable} of the most recent job execution. */
  String stepTableOfLastExecution() {
    return stepTable(
        jdbc.sql("SELECT max(job_execution_id) FROM batch_job_execution")
            .query(Long.class)
            .single());
  }

  @Override
  public void close() {
    app.close();
  }
}
