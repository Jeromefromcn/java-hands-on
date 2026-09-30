package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.domain.loan.LoanStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Spring Data access to the entity. Package-private: nothing outside the adapter sees entities. */
interface LoanPositionJpaRepository extends JpaRepository<LoanPositionEntity, String> {

  List<LoanPositionEntity> findByCounterpartyIdAndStatusNotOrderById(
      String counterpartyId, LoanStatus status);

  /**
   * The row, locked for the rest of the transaction. A waiter gives up after 3 seconds rather than
   * holding its connection for as long as the holder takes.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
  @Query("select p from LoanPositionEntity p where p.id = :id")
  Optional<LoanPositionEntity> findForUpdate(@Param("id") String id);
}
