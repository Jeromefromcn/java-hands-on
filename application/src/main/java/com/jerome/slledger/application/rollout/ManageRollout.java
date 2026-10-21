package com.jerome.slledger.application.rollout;

import com.jerome.slledger.application.audit.Audit;
import com.jerome.slledger.application.audit.AuditEvent.Result;
import com.jerome.slledger.application.position.UnknownReferenceException;
import com.jerome.slledger.application.rollout.RolloutRefusedException.Reason;
import com.jerome.slledger.application.security.Callers;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raising and lowering a rollout under {@link RolloutRules}, every change and every refusal on the
 * audit trail.
 *
 * <p>A change and its audit line are one transaction ({@link Audit#rollout}); a refusal is audited
 * in its own ({@link Audit#rolloutRefused}) before the refusing transaction rolls back, as stage 13
 * does for bookings. Who may call these at all (operations staff) is the edge's question; who may
 * approve <em>this</em> raise (not its maker) is this class's.
 */
public class ManageRollout {

  private final RolloutStore store;
  private final RolloutRules rules;
  private final Audit audit;
  private final Callers callers;
  private final Clock clock;

  public ManageRollout(
      RolloutStore store, RolloutRules rules, Audit audit, Callers callers, Clock clock) {
    this.store = store;
    this.rules = rules;
    this.audit = audit;
    this.callers = callers;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Rollout show(String flag) {
    return current(flag);
  }

  @Transactional(readOnly = true)
  public List<RolloutChange> changes(String flag, int limit) {
    current(flag);
    return store.changes(flag, limit);
  }

  @Transactional
  public RolloutChange proposeRaise(String flag, int toPercent) {
    Rollout current = current(flag);
    guarded(flag, () -> rules.checkProposal(current, toPercent));
    RolloutChange change =
        store.propose(flag, current.percent(), toPercent, callers.current().subject(), now());
    audit.rollout(Result.PROPOSED, flag, step(change.id(), current.percent(), toPercent));
    return change;
  }

  @Transactional
  public Rollout approve(String flag, long changeId) {
    Rollout current = current(flag);
    RolloutChange change =
        store
            .change(flag, changeId)
            .orElseThrow(
                () -> UnknownReferenceException.subject("rollout change", flag + "#" + changeId));
    Instant now = now();
    String checker = callers.current().subject();
    guarded(flag, () -> rules.checkApproval(current, change, checker, now));
    if (!store.move(current, change.toPercent(), now)) {
      refuse(flag, new RolloutRefusedException(Reason.STALE, "The rollout moved meanwhile"));
    }
    store.applied(changeId, checker, now);
    store.supersedeOpen(flag, now);
    audit.rollout(Result.APPROVED, flag, step(changeId, current.percent(), change.toPercent()));
    return current(flag);
  }

  /** The kill switch, and any step back: one person, effective now. */
  @Transactional
  public Rollout lower(String flag, int toPercent) {
    Rollout current = current(flag);
    guarded(flag, () -> rules.checkLowering(current, toPercent));
    Instant now = now();
    if (!store.move(current, toPercent, now)) {
      refuse(flag, new RolloutRefusedException(Reason.STALE, "The rollout moved meanwhile"));
    }
    store.supersedeOpen(flag, now);
    audit.rollout(Result.LOWERED, flag, "%d->%d".formatted(current.percent(), toPercent));
    return current(flag);
  }

  private Rollout current(String flag) {
    return store.find(flag).orElseThrow(() -> UnknownReferenceException.subject("rollout", flag));
  }

  private void guarded(String flag, Runnable check) {
    try {
      check.run();
    } catch (RolloutRefusedException e) {
      refuse(flag, e);
    }
  }

  private void refuse(String flag, RolloutRefusedException e) {
    audit.rolloutRefused(flag, e.reason().name());
    throw e;
  }

  private Instant now() {
    return clock.instant();
  }

  /** "change 7: 0->5": ids and percentages only, like every audit line. */
  private static String step(long changeId, int from, int to) {
    return "change %d: %d->%d".formatted(changeId, from, to);
  }
}
