package com.jerome.slledger.application.revaluation;

import com.jerome.slledger.domain.instrument.Isin;
import java.util.List;
import java.util.OptionalLong;

/** Requests to revalue a security's positions in a batch, one per (security, price version). */
public interface RevaluationRequests {

  record Pending(Isin security, long priceVersion, int attempts) {}

  /**
   * Idempotent: asking twice for one (security, version) leaves one request. In the caller's
   * transaction.
   */
  void request(Isin security, long priceVersion);

  /** Oldest first. */
  List<Pending> pending(int limit);

  /** The security's stored price version, for telling a request that has been overtaken. */
  OptionalLong currentPriceVersion(Isin security);

  void markDone(Isin security, long priceVersion);

  void markSuperseded(Isin security, long priceVersion);

  /** Counts one launch; a request whose attempts pass the limit is set to {@code FAILED}. */
  void recordAttempt(Isin security, long priceVersion, int maxAttempts);
}
