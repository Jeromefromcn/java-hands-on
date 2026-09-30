package com.jerome.slledger.application.saga;

import com.jerome.slledger.application.position.FindBookedTrade;
import com.jerome.slledger.application.position.OpenPositionCommand;
import com.jerome.slledger.application.saga.SagaLog.Outcome;
import com.jerome.slledger.application.saga.SagaLog.Phase;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Opening a position across three participants: credit, collateral, and the ledger. An
 * orchestrator: it runs the steps in order and, on a failure, runs the compensations, and it writes
 * what it is about to do to a log before doing it.
 *
 * <p>The saga id is the command's idempotency key. That makes a replay of a finished saga a lookup,
 * and lets recovery ask the ledger the one question the log cannot always answer: did the position
 * get created?
 *
 * <p><b>Steps before the pivot</b> (reserve credit, freeze collateral) have compensations and are
 * undone on a failure. <b>The pivot</b> (creating the position) is one local transaction; it either
 * happened or it did not. <b>Steps after it</b> (spending the hold and the reservation) are never
 * undone: the position exists, so the saga can only go forward, and it retries until it does.
 *
 * <p>Compensations do not depend on the log being right about what ran. Each is idempotent and safe
 * on a step that never happened (the hold's cancel writes a mark that refuses a late Try), so a
 * crash between "log the step" and "run the step", or between "run it" and "log it", needs no
 * special case: compensate everything.
 *
 * <p>Not an outbox. The outbox makes one database write and one message atomic; this makes a
 * sequence of writes in several places recoverable. The {@code PositionOpened} event is still
 * appended by the pivot's own transaction.
 */
public class OpenPositionSaga {

  private final OpenPositionSteps steps;
  private final SagaLog log;
  private final FindBookedTrade booked;
  private final FailurePoint failures;
  private final int completionAttempts;

  public OpenPositionSaga(
      OpenPositionSteps steps,
      SagaLog log,
      FindBookedTrade booked,
      FailurePoint failures,
      int completionAttempts) {
    this.steps = steps;
    this.log = log;
    this.booked = booked;
    this.failures = failures;
    this.completionAttempts = completionAttempts;
  }

  /** A forward step failed. Carries the step so the caller can be told which. */
  private static final class StepFailed extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final String step;

    StepFailed(String step, RuntimeException cause) {
      super(step, cause);
      this.step = step;
    }

    RuntimeException original() {
      return (RuntimeException) getCause();
    }
  }

  public SagaResult run(OpenPositionCommand command) {
    String id = command.key().value();
    Optional<Phase> finished = log.terminal(id);
    if (finished.isPresent()) {
      return replay(finished.get());
    }
    if (!log.read(id).isEmpty()) {
      throw new IllegalStateException(
          "Saga " + id + " is unfinished; recover it, do not start it again");
    }

    log.record(id, SagaLog.SAGA, Phase.FORWARD, Outcome.STARTED, null);
    try {
      forward(id, "reserveCredit", () -> steps.reserveCredit(id, command));
      forward(id, "freezeCollateral", () -> steps.freezeCollateral(id, command));
      forward(id, "createPosition", () -> steps.createPosition(command));
    } catch (StepFailed failed) {
      return compensate(id, failed.step, failed.original());
    }
    return completeForward(id);
  }

  /**
   * Finishes a saga that has no terminal entry: rolls it forward if the position exists,
   * compensates it if not. The ledger is asked, not the log: the log can be one entry behind.
   */
  public SagaResult recover(String sagaId) {
    Optional<Phase> finished = log.terminal(sagaId);
    if (finished.isPresent()) {
      return replay(finished.get());
    }
    if (booked.byKey(new IdempotencyKey(sagaId)).isPresent()) {
      return completeForward(sagaId);
    }
    return compensate(sagaId, "recovery", null);
  }

  private static SagaResult replay(Phase terminal) {
    return terminal == Phase.FORWARD
        ? SagaResult.completed()
        : new SagaResult(SagaResult.Status.COMPENSATED, null, null);
  }

  private void forward(String id, String step, Runnable action) {
    log.record(id, step, Phase.FORWARD, Outcome.STARTED, null);
    failures.reached("before:" + step);
    try {
      action.run();
    } catch (RuntimeException e) {
      log.record(id, step, Phase.FORWARD, Outcome.FAILED, String.valueOf(e.getMessage()));
      throw new StepFailed(step, e);
    }
    failures.reached("after:" + step);
    log.record(id, step, Phase.FORWARD, Outcome.DONE, null);
  }

  private SagaResult completeForward(String id) {
    RuntimeException last = null;
    for (int attempt = 1; attempt <= completionAttempts; attempt++) {
      log.record(id, "complete", Phase.FORWARD, Outcome.STARTED, "attempt " + attempt);
      failures.reached("before:complete");
      try {
        steps.complete(id);
      } catch (RuntimeException e) {
        last = e;
        log.record(id, "complete", Phase.FORWARD, Outcome.FAILED, String.valueOf(e.getMessage()));
        continue;
      }
      failures.reached("after:complete");
      log.record(id, "complete", Phase.FORWARD, Outcome.DONE, null);
      log.record(id, SagaLog.SAGA, Phase.FORWARD, Outcome.DONE, null);
      return SagaResult.completed();
    }
    return new SagaResult(SagaResult.Status.PENDING_COMPLETION, "complete", last);
  }

  private SagaResult compensate(String id, String failedStep, RuntimeException cause) {
    boolean everythingUndone = true;
    List<String> names = List.of("cancelHold", "releaseReservation");
    List<Consumer<String>> actions = List.of(steps::cancelHold, steps::releaseReservation);
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      log.record(id, name, Phase.COMPENSATE, Outcome.STARTED, null);
      failures.reached("before:" + name);
      try {
        actions.get(i).accept(id);
      } catch (RuntimeException e) {
        // Logged, not swallowed: the saga stays unfinished and recovery retries it.
        log.record(id, name, Phase.COMPENSATE, Outcome.FAILED, String.valueOf(e.getMessage()));
        everythingUndone = false;
        continue;
      }
      failures.reached("after:" + name);
      log.record(id, name, Phase.COMPENSATE, Outcome.DONE, null);
    }
    if (!everythingUndone) {
      return new SagaResult(SagaResult.Status.COMPENSATION_INCOMPLETE, failedStep, cause);
    }
    log.record(id, SagaLog.SAGA, Phase.COMPENSATE, Outcome.DONE, null);
    return new SagaResult(SagaResult.Status.COMPENSATED, failedStep, cause);
  }
}
