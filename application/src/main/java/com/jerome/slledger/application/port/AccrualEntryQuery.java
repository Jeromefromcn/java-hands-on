package com.jerome.slledger.application.port;

import com.jerome.slledger.domain.accrual.AccrualEntry;
import com.jerome.slledger.domain.loan.LoanPositionId;
import java.time.LocalDate;
import java.util.List;

/** A position's accrual entries between two business dates, both inclusive, ordered by date. */
public interface AccrualEntryQuery {

  List<AccrualEntry> of(LoanPositionId position, LocalDate from, LocalDate to);
}
