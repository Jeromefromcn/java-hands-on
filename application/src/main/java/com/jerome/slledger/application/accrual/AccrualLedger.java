package com.jerome.slledger.application.accrual;

import com.jerome.slledger.domain.accrual.AccrualEntry;
import java.util.List;

/**
 * Where accrual entries are booked. Booking is idempotent by the entry's key, the position and the
 * business date: an entry the store already holds is left alone and counted, never overwritten and
 * never an error. That is what lets a job be run twice, and be restarted from before its last
 * commit, without a fee booked twice.
 *
 * <p>Takes part in the caller's transaction.
 */
public interface AccrualLedger {

  BookResult book(List<AccrualEntry> entries);
}
