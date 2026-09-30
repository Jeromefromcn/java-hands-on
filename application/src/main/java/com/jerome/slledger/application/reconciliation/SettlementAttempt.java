package com.jerome.slledger.application.reconciliation;

import com.jerome.slledger.application.reconciliation.SettlementBreaks.Due;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * One rung of the ladder, in one transaction: claim the next due break, ask the custodian again,
 * and record what came of it. A separate bean from {@link SettlementRecovery}, which loops, so that
 * each attempt is a call through the transactional proxy and not a call from inside the class.
 *
 * <p>The transaction is held across the call to the custodian, as the outbox relay holds its across
 * a send: the claim is the row lock, and releasing it before the answer is known would let a second
 * instance resubmit the same settlement.
 */
public class SettlementAttempt {

  public enum Outcome {
    RESOLVED,
    RETRY_SCHEDULED,
    ESCALATED
  }

  public record Result(String positionId, int attempt, Outcome outcome, String detail) {}

  private final SettlementBreaks breaks;
  private final SettlementGateway gateway;
  private final Clock clock;
  private final int maxAttempts;
  private final List<Duration> delays;

  /**
   * @param maxAttempts resubmissions before the break goes to a person
   * @param delays the wait after the first failed attempt, after the second, and so on; the last
   *     repeats if there are more attempts than delays
   */
  public SettlementAttempt(
      SettlementBreaks breaks,
      SettlementGateway gateway,
      Clock clock,
      int maxAttempts,
      List<Duration> delays) {
    if (maxAttempts < 1 || delays.isEmpty()) {
      throw new IllegalArgumentException("a ladder needs at least one attempt and one delay");
    }
    this.breaks = breaks;
    this.gateway = gateway;
    this.clock = clock;
    this.maxAttempts = maxAttempts;
    this.delays = List.copyOf(delays);
  }

  /**
   * @return what happened to the break it claimed, or empty when none is due
   */
  @Transactional
  public Optional<Result> attemptNext() {
    Optional<Due> claimed = breaks.claimNextDue(clock.instant());
    if (claimed.isEmpty()) {
      return Optional.empty();
    }
    Due due = claimed.get();
    int attempt = due.attempts() + 1;
    String failure;
    try {
      SettlementGateway.Result answer = gateway.resubmit(due.positionId());
      if (answer instanceof SettlementGateway.Settled) {
        breaks.resolve(due.snapshot(), due.positionId(), attempt, clock.instant());
        return Optional.of(new Result(due.positionId(), attempt, Outcome.RESOLVED, "settled"));
      }
      failure = ((SettlementGateway.Rejected) answer).reason();
    } catch (RuntimeException e) {
      failure = e.getClass().getSimpleName() + ": " + e.getMessage();
    }
    if (attempt >= maxAttempts) {
      breaks.escalate(due.snapshot(), due.positionId(), attempt, failure);
      return Optional.of(new Result(due.positionId(), attempt, Outcome.ESCALATED, failure));
    }
    Duration wait = delays.get(Math.min(attempt, delays.size()) - 1);
    breaks.scheduleRetry(
        due.snapshot(), due.positionId(), attempt, clock.instant().plus(wait), failure);
    return Optional.of(new Result(due.positionId(), attempt, Outcome.RETRY_SCHEDULED, failure));
  }
}
