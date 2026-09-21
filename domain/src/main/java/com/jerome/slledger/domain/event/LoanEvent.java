package com.jerome.slledger.domain.event;

import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Everything that can happen to a position, as a closed set.
 *
 * <p>Sealed, so the compiler knows the list is complete. That is worth more than it first looks: a
 * {@code switch} over these with no {@code default} branch will not compile once a fifth event is
 * permitted, so adding one is a build failure at every place that has to care rather than a silent
 * fall-through at every place that forgot. An {@code default -> throw} would give the same
 * guarantee at run time, on the customer's machine, a month later.
 *
 * <p>{@code occurredAt} is an {@link Instant}: machine time, UTC, when the thing happened. Business
 * dates on these events are {@link LocalDate} and are separate fields — see {@link Accrued}, which
 * carries both. See {@code docs/decisions/02-domain-model.md}.
 */
public sealed interface LoanEvent {

  LoanPositionId position();

  Instant occurredAt();

  /** A position was opened: securities out, collateral in. */
  record Opened(
      LoanPositionId position,
      Instant occurredAt,
      CounterpartyId counterparty,
      Isin security,
      long quantity,
      Money collateral)
      implements LoanEvent {

    public Opened {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(occurredAt, "occurredAt");
      Objects.requireNonNull(counterparty, "counterparty");
      Objects.requireNonNull(security, "security");
      Objects.requireNonNull(collateral, "collateral");
    }
  }

  /**
   * Securities came back. Carries the balance after the return as well as the amount returned, so
   * that a reader of the event stream never has to replay it to know where the position stood.
   */
  record Returned(LoanPositionId position, Instant occurredAt, long quantity, long outstandingAfter)
      implements LoanEvent {

    public Returned {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  /**
   * A day's fee was booked.
   *
   * <p>Two time fields, deliberately. {@code occurredAt} is when the batch ran; {@code accrualDate}
   * is the business day being charged for. They are usually a few hours apart and occasionally a
   * day apart, and collapsing them means the accrual date moves when somebody reschedules the
   * batch.
   */
  record Accrued(LoanPositionId position, Instant occurredAt, LocalDate accrualDate, Money fee)
      implements LoanEvent {

    public Accrued {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(occurredAt, "occurredAt");
      Objects.requireNonNull(accrualDate, "accrualDate");
      Objects.requireNonNull(fee, "fee");
    }
  }

  /** Collateral no longer covers the position. */
  record MarginCalled(LoanPositionId position, Instant occurredAt, Money shortfall)
      implements LoanEvent {

    public MarginCalled {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(occurredAt, "occurredAt");
      Objects.requireNonNull(shortfall, "shortfall");
    }
  }
}
