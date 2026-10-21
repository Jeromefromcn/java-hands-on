package com.jerome.slledger.application.audit;

import com.jerome.slledger.application.audit.AuditEvent.Action;
import com.jerome.slledger.application.audit.AuditEvent.Result;
import com.jerome.slledger.application.audit.AuditEvent.Subject;
import com.jerome.slledger.application.security.Callers;
import java.time.Clock;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit trail, with the two transaction rules that make it true.
 *
 * <p>A booking is audited <em>inside</em> the booking's transaction ({@link #booked}): the line
 * commits with the trade and rolls back with it, because "position opened" about a position that
 * was rolled back is false. {@code MANDATORY} makes that a rule and not a habit: called with no
 * transaction it fails, rather than writing a line that outlives nothing.
 *
 * <p>A refusal is audited <em>outside</em> it ({@link #refused}, {@link #denied}), in its own
 * transaction, after the booking has rolled back: an attempt that booked nothing is exactly what an
 * investigation looks for, and the rollback must not take its record with it.
 */
public class Audit {

  /** Audits nothing and is nobody: for the use-case tests that are not about the trail. */
  public static final Audit NONE =
      new Audit(AuditStore.NONE, Callers.SYSTEM_ONLY, Clock.systemUTC());

  private final AuditStore store;
  private final Callers callers;
  private final Clock clock;

  public Audit(AuditStore store, Callers callers, Clock clock) {
    this.store = store;
    this.callers = callers;
    this.clock = clock;
  }

  /** The booking in progress succeeded for {@code positionId}; commits or rolls back with it. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void booked(Action action, String positionId) {
    store.append(event(action, Subject.POSITION, positionId, Result.BOOKED, ""));
  }

  /** A booking request booked nothing; survives the rollback of the transaction that failed. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void refused(Action action, String idempotencyKey, String reason) {
    store.append(event(action, Subject.REQUEST, idempotencyKey, Result.REJECTED, reason));
  }

  /** A request was refused at the edge, for {@code method path}. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void denied(String endpoint) {
    store.append(event(Action.ACCESS, Subject.ENDPOINT, endpoint, Result.DENIED, ""));
  }

  /** A rollout changed; commits or rolls back with the change. Stage 14. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void rollout(Result result, String flag, String step) {
    store.append(event(Action.ROLLOUT, Subject.FLAG, flag, result, step));
  }

  /** A rollout change was refused by its rules; survives the rollback. Stage 14. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void rolloutRefused(String flag, String reason) {
    store.append(event(Action.ROLLOUT, Subject.FLAG, flag, Result.REJECTED, reason));
  }

  private AuditEvent event(
      Action action, Subject subject, String subjectId, Result result, String reason) {
    return new AuditEvent(
        clock.instant(), callers.current().subject(), action, subject, subjectId, result, reason);
  }
}
