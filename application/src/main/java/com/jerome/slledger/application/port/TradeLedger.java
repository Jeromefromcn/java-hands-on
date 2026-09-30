package com.jerome.slledger.application.port;

import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanTrade;
import java.util.Optional;

/** Where booked trades are recorded. Trades are appended, never changed. */
public interface TradeLedger {

  /**
   * Records the trade and the fingerprint of the request that booked it.
   *
   * @throws DuplicateRequestException if a trade with the same idempotency key is already booked,
   *     in either direction. Raised here, inside the caller's transaction, not at commit: the
   *     caller's transaction must roll back everything it did for this submission.
   */
  void book(LoanTrade trade, RequestFingerprint fingerprint);

  /** The trade booked under {@code key}, if its transaction has committed. */
  Optional<BookedTrade> findByKey(IdempotencyKey key);
}
