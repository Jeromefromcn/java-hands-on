package com.jerome.slledger.application.port;

import com.jerome.slledger.domain.loan.LoanTrade;

/** Where booked trades are recorded. Trades are appended, never changed. */
public interface TradeLedger {

  /**
   * Records the trade.
   *
   * @throws DuplicateRequestException if a trade with the same idempotency key is already booked,
   *     in either direction. Raised here, inside the caller's transaction, not at commit: the
   *     caller's transaction must roll back everything it did for this submission.
   */
  void book(LoanTrade trade);
}
