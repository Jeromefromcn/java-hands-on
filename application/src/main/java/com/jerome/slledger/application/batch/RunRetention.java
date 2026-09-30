package com.jerome.slledger.application.batch;

import java.util.function.IntSupplier;

/** Repeats a purge batch until one removes fewer rows than a full batch. */
public class RunRetention {

  private final RetentionPurges purges;

  public RunRetention(RetentionPurges purges) {
    this.purges = purges;
  }

  public long purgeOutbox() {
    return drain(purges::outboxBatch);
  }

  public long purgeTombstones() {
    return drain(purges::tombstoneBatch);
  }

  public long purgeSagas() {
    return drain(purges::sagaBatch);
  }

  private long drain(IntSupplier batch) {
    long total = 0;
    int removed;
    do {
      removed = batch.getAsInt();
      total += removed;
    } while (removed >= purges.batchSize());
    return total;
  }
}
