package com.jerome.slledger.adapter.web.loan;

import com.jerome.slledger.domain.accrual.AccrualEntry;
import com.jerome.slledger.domain.money.Money;
import java.time.LocalDate;
import java.util.List;

/** A position's accrual entries over the range asked for. */
public record AccrualsView(String positionId, LocalDate from, LocalDate to, List<Entry> entries) {

  public record Entry(LocalDate businessDate, Money fee) {}

  static AccrualsView of(
      String positionId, LocalDate from, LocalDate to, List<AccrualEntry> found) {
    return new AccrualsView(
        positionId, from, to, found.stream().map(e -> new Entry(e.accruedOn(), e.fee())).toList());
  }
}
