package com.jerome.slledger.application.reconciliation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The settlement failure ladder: for a position the ledger holds and the custodian, past its
 * settlement window, does not, resubmit the settlement, wait, and resubmit, up to a limit; then
 * escalate to a person.
 *
 * <ul>
 *   <li><b>Trigger</b>: a break of kind {@code OURS_ONLY} past the window, recorded by the
 *       reconciliation with disposition {@code RETRY_SETTLEMENT} and a first attempt due at once.
 *   <li><b>Retries</b>: {@code maxAttempts} resubmissions, spaced by the configured delays.
 *   <li><b>Escalation</b>: after the last failure the break is {@code ESCALATED} and is never
 *       retried by the ladder again; it is in the queue a person works.
 * </ul>
 *
 * <p>It only ever retries forward. It reverses nothing in the ledger: the position exists and its
 * fees accrue, and whether to unwind it is a decision for the person the break is escalated to.
 */
public class SettlementRecovery {

  private final SettlementAttempt attempt;

  public SettlementRecovery(SettlementAttempt attempt) {
    this.attempt = attempt;
  }

  /**
   * Runs every attempt that is due now, each in its own transaction.
   *
   * @return what each attempt did, in order
   */
  public List<SettlementAttempt.Result> run() {
    List<SettlementAttempt.Result> done = new ArrayList<>();
    for (Optional<SettlementAttempt.Result> next = attempt.attemptNext();
        next.isPresent();
        next = attempt.attemptNext()) {
      done.add(next.get());
    }
    return done;
  }
}
