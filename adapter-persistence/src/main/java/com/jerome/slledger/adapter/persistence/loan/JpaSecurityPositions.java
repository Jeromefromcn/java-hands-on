package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.application.messaging.SecurityPositions;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.loan.LoanStatus;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Positions in a security, for revaluation. Read only: the entities are mapped to domain positions
 * and dropped, and nothing is registered with the unit of work, so a revaluation cannot write a
 * position back.
 */
@Repository
public class JpaSecurityPositions implements SecurityPositions {

  private final LoanPositionJpaRepository jpa;

  JpaSecurityPositions(LoanPositionJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public List<LoanPosition> openIn(Isin security) {
    return jpa.findByIsinAndStatusNotOrderById(security.value(), LoanStatus.CLOSED).stream()
        .map(LoanPositionMapper::toDomain)
        .toList();
  }

  @Override
  public long countOpenIn(Isin security) {
    return jpa.countByIsinAndStatusNot(security.value(), LoanStatus.CLOSED);
  }
}
