package com.jerome.slledger.application.saga;

import java.util.ArrayList;
import java.util.List;

/**
 * Finishes every saga that has entries and no end. Run at start-up and on a timer: a saga is
 * unfinished exactly when the process that was running it stopped, and nothing else will finish it.
 */
public class SagaRecovery {

  private final SagaLog log;
  private final OpenPositionSaga saga;

  public SagaRecovery(SagaLog log, OpenPositionSaga saga) {
    this.log = log;
    this.saga = saga;
  }

  /**
   * @return the result of each saga it touched, by id order
   */
  public List<SagaResult> recoverAll() {
    List<SagaResult> results = new ArrayList<>();
    for (String id : log.unfinished()) {
      results.add(saga.recover(id));
    }
    return results;
  }
}
