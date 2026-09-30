package com.jerome.slledger.batch.accrual;

import com.jerome.slledger.application.accrual.PartitionScheme;

/**
 * The knobs of the accrual job, from {@code ledger.batch.*}. A chunk is one transaction, so its
 * size is the trade between commit overhead and how much a failure redoes.
 */
public record BatchSettings(
    int chunkSize,
    int skipLimit,
    int retryLimit,
    PartitionScheme partitionScheme,
    int partitions,
    int workerThreads) {

  public BatchSettings {
    if (chunkSize < 1) {
      throw new IllegalArgumentException("chunkSize must be positive, was " + chunkSize);
    }
    if (skipLimit < 0 || retryLimit < 0) {
      throw new IllegalArgumentException("limits must not be negative");
    }
    if (partitions < 1 || workerThreads < 1) {
      throw new IllegalArgumentException("partitions and workerThreads must be positive");
    }
  }

  public static BatchSettings defaults() {
    return new BatchSettings(200, 10, 3, PartitionScheme.NONE, 4, 4);
  }
}
