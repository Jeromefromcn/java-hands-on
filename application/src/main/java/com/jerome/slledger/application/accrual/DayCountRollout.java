package com.jerome.slledger.application.accrual;

import com.jerome.slledger.application.rollout.Bucket;
import com.jerome.slledger.domain.accrual.DayCount;
import com.jerome.slledger.domain.loan.LoanPosition;
import java.util.Currency;

/**
 * Which day count a position accrues on, under the rollout of {@link #FLAG}: a counterparty on the
 * rollout accrues every position on its currency's market convention, any other on ACT/365F, as the
 * ledger always did.
 *
 * <p>A value, read once per accrual run: the job takes the percentage when it starts and keeps it
 * for the run and every restart of it, so one business date is never booked under two rules. See
 * docs/decisions/14-delivery.md.
 */
public record DayCountRollout(int percent) {

  public static final String FLAG = "accrual.day-count-by-market";

  /** Nobody on the rollout: the ledger before stage 14. */
  public static final DayCountRollout OFF = new DayCountRollout(0);

  public DayCount dayCountFor(LoanPosition position, Currency currency) {
    return Bucket.selected(FLAG, position.counterparty(), percent)
        ? DayCount.marketConvention(currency)
        : DayCount.ACT_365F;
  }
}
