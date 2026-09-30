package com.jerome.slledger.application.revaluation;

import com.jerome.slledger.application.accrual.AccrualCandidate;
import com.jerome.slledger.domain.instrument.Isin;
import java.util.List;
import java.util.Optional;

/** One security's open positions with the security's current price, in pages by key. */
public interface RevaluationCandidates {

  /**
   * Up to {@code limit} candidates with an id greater than {@code afterId}, in id order. A row that
   * cannot be restored is a {@link AccrualCandidate.Corrupt}, not an exception, as for the accrual.
   */
  List<AccrualCandidate> page(Isin security, Optional<String> afterId, int limit);
}
