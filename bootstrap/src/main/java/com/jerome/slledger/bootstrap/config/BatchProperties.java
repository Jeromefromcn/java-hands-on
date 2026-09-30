package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.application.accrual.PartitionScheme;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Batch. Off unless {@code enabled} is true: with it off there is no job repository, no job, no
 * launcher and no thread.
 *
 * @param chunkSize items per transaction; also the page size the reader fetches. A failure redoes
 *     at most one chunk, and the commit overhead is paid once per chunk
 * @param skipLimit how many items one step may skip before it fails; 0 fails on the first
 * @param retryLimit tries of a chunk that fails with a transient data-access error
 * @param partitionScheme how the accrual's keyspace is cut into parallel slices
 * @param partitions the target number of slices, used by the balanced scheme
 * @param workerThreads threads that run partitions
 * @param lockTtl how long the cutover lock lasts without renewal; longer than the longest accrual,
 *     or another instance may take the date over while it is still running (the fence makes that
 *     safe and the cost is a run that has to be resumed)
 */
@Validated
@ConfigurationProperties(prefix = "ledger.batch")
public record BatchProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("200") @Min(1) int chunkSize,
    @DefaultValue("10") @Min(0) int skipLimit,
    @DefaultValue("3") @Min(0) int retryLimit,
    @DefaultValue("NONE") @NotNull PartitionScheme partitionScheme,
    @DefaultValue("4") @Min(1) int partitions,
    @DefaultValue("4") @Min(1) int workerThreads,
    @DefaultValue("10m") @NotNull Duration lockTtl) {}
