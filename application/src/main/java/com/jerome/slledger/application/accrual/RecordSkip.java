package com.jerome.slledger.application.accrual;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes a skip record in its own transaction; see {@link SkippedItems}. */
public class RecordSkip {

  private final SkippedItems skipped;

  public RecordSkip(SkippedItems skipped) {
    this.skipped = skipped;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(String job, String scope, String itemKey, String phase, String reason) {
    skipped.record(job, scope, itemKey, phase, reason);
  }
}
