package com.jerome.slledger.application.position;

import com.jerome.slledger.application.port.BookedTrade;
import com.jerome.slledger.application.port.TradeLedger;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * The committed trade booked under a key, read in a transaction of its own. A separate bean so that
 * {@link IdempotentBooking}, which is deliberately not transactional, calls it through its proxy
 * (stage 5's self-invocation finding).
 */
public class FindBookedTrade {

  private final TradeLedger trades;

  public FindBookedTrade(TradeLedger trades) {
    this.trades = trades;
  }

  @Transactional(readOnly = true)
  public Optional<BookedTrade> byKey(IdempotencyKey key) {
    return trades.findByKey(key);
  }
}
