package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.application.port.BookedTrade;
import com.jerome.slledger.application.port.DuplicateRequestException;
import com.jerome.slledger.application.port.RequestFingerprint;
import com.jerome.slledger.application.port.TradeLedger;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanTrade;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/**
 * Trades, with the idempotency key enforced by the database's unique constraint.
 *
 * <p>The insert is flushed here, not left to commit, so that a duplicate key surfaces as a {@link
 * DuplicateRequestException} inside the use case, which then rolls back everything it did for the
 * submission. Left to commit, it would surface as a generic failure after the use case had
 * returned.
 */
@Repository
public class JpaTradeLedger implements TradeLedger {

  /** Postgres's default name for {@code idempotency_key text NOT NULL UNIQUE} on loan_trade. */
  static final String IDEMPOTENCY_CONSTRAINT = "loan_trade_idempotency_key_key";

  private final LoanTradeJpaRepository jpa;

  JpaTradeLedger(LoanTradeJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public void book(LoanTrade trade, RequestFingerprint fingerprint) {
    try {
      jpa.saveAndFlush(LoanTradeMapper.toEntity(trade, fingerprint));
    } catch (DataIntegrityViolationException e) {
      if (violates(e, IDEMPOTENCY_CONSTRAINT)) {
        throw new DuplicateRequestException(trade.idempotencyKey(), e);
      }
      throw e;
    }
  }

  @Override
  public Optional<BookedTrade> findByKey(IdempotencyKey key) {
    return jpa.findByIdempotencyKey(key.value()).map(LoanTradeMapper::toBooked);
  }

  private static boolean violates(Throwable failure, String constraint) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof ConstraintViolationException violation) {
        return constraint.equals(violation.getConstraintName());
      }
    }
    return false;
  }
}
