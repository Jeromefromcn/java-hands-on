package com.jerome.slledger.adapter.persistence.loan;

import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Positions with pessimistic locking: {@link #find} locks the row until the transaction ends, so a
 * second writer waits instead of failing at flush. The wait is bounded by {@link
 * LoanPositionJpaRepository#findForUpdate}'s lock timeout.
 *
 * <p>The {@code version} column is still checked at flush. Under this lock it never fails, because
 * nobody else can change the row between the read and the write.
 */
@Repository
@ConditionalOnProperty(name = "ledger.persistence.locking", havingValue = "pessimistic")
public class PessimisticLoanPositionRepository extends UnitOfWorkLoanPositionRepository {

  public PessimisticLoanPositionRepository(
      LoanPositionJpaRepository jpa, EntityManager entityManager, UnitOfWorkPositions unitOfWork) {
    super(jpa, entityManager, unitOfWork);
  }

  @Override
  Optional<LoanPositionEntity> load(String id) {
    return jpa.findForUpdate(id);
  }
}
