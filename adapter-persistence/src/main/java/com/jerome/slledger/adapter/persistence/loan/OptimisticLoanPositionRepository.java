package com.jerome.slledger.adapter.persistence.loan;

import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Positions with optimistic locking: no lock is taken on read, and a concurrent write is detected
 * at flush by the {@code version} column. The default.
 */
@Repository
@ConditionalOnProperty(
    name = "ledger.persistence.locking",
    havingValue = "optimistic",
    matchIfMissing = true)
public class OptimisticLoanPositionRepository extends UnitOfWorkLoanPositionRepository {

  public OptimisticLoanPositionRepository(
      LoanPositionJpaRepository jpa, EntityManager entityManager, UnitOfWorkPositions unitOfWork) {
    super(jpa, entityManager, unitOfWork);
  }

  @Override
  Optional<LoanPositionEntity> load(String id) {
    return jpa.findById(id);
  }
}
