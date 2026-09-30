package com.jerome.slledger.application.batch;

import java.time.Clock;
import org.springframework.transaction.annotation.Transactional;

/**
 * One bounded batch of each purge, in one transaction. {@link RunRetention} loops over them; it is
 * a separate class so that each batch is a call through the transactional proxy.
 */
public class RetentionPurges {

  private final Retention retention;
  private final Clock clock;
  private final RetentionPolicy policy;

  public RetentionPurges(Retention retention, Clock clock, RetentionPolicy policy) {
    this.retention = retention;
    this.clock = clock;
    this.policy = policy;
  }

  public int batchSize() {
    return policy.batchSize();
  }

  @Transactional
  public int outboxBatch() {
    return retention.purgeOutbox(clock.instant().minus(policy.outbox()), policy.batchSize());
  }

  @Transactional
  public int tombstoneBatch() {
    return retention.purgeTombstones(
        clock.instant().minus(policy.tombstones()), policy.batchSize());
  }

  @Transactional
  public int sagaBatch() {
    return retention.purgeFinishedSagas(clock.instant().minus(policy.sagas()), policy.batchSize());
  }
}
