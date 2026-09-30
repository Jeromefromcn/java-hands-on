package com.jerome.slledger.application.revaluation;

import com.jerome.slledger.domain.instrument.Isin;

/** Starts the revaluation of one security at one price version, and reports how it ended. */
public interface RevaluationLauncher {

  enum Result {
    COMPLETED,
    FAILED,
    /** Already completed, or running elsewhere: nothing to do here. */
    NOT_STARTED
  }

  Result launch(Isin security, long priceVersion);
}
