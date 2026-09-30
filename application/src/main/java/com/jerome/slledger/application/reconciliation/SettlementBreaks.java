package com.jerome.slledger.application.reconciliation;

import java.time.Instant;
import java.util.Optional;

/** The breaks the settlement ladder works on, claimed one at a time. */
public interface SettlementBreaks {

  record Due(String snapshot, String positionId, int attempts) {}

  /**
   * The next break in {@link Disposition#RETRY_SETTLEMENT} whose retry time has come, locked for
   * the rest of the caller's transaction and skipping any another instance has locked, so two
   * instances never resubmit the same settlement.
   */
  Optional<Due> claimNextDue(Instant now);

  void resolve(String snapshot, String positionId, int attempts, Instant at);

  void scheduleRetry(
      String snapshot, String positionId, int attempts, Instant nextAttemptAt, String lastFailure);

  void escalate(String snapshot, String positionId, int attempts, String lastFailure);
}
