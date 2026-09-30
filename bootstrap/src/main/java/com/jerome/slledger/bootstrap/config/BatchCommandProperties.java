package com.jerome.slledger.bootstrap.config;

import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Run one job once, when the application starts, and optionally exit: what a cron entry or a
 * Kubernetes Job uses. Empty {@code job} (the default) runs nothing.
 *
 * <p>{@code ledger.batch.command.job} is one of {@code accrual}, {@code reconciliation} or {@code
 * retention}. {@code date} defaults to today in Hong Kong. The accrual and retention take nothing
 * else; the reconciliation takes {@code snapshot-id} and {@code snapshot-file}.
 *
 * @param rerun an identifying number that makes the run a second instance of its date or snapshot:
 *     the operator's "recompute it on purpose"
 * @param exitAfterRun stop the application when the job has ended; the exit code is 0 for a
 *     completed job and 1 otherwise
 */
@ConfigurationProperties(prefix = "ledger.batch.command")
public record BatchCommandProperties(
    @DefaultValue("") String job,
    LocalDate date,
    String snapshotId,
    String snapshotFile,
    Long rerun,
    @DefaultValue("false") boolean exitAfterRun) {}
