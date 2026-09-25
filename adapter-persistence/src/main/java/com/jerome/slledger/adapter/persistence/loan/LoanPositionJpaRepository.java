package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.domain.loan.LoanStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to the entity. Package-private: nothing outside the adapter sees entities. */
interface LoanPositionJpaRepository extends JpaRepository<LoanPositionEntity, String> {

  List<LoanPositionEntity> findByCounterpartyIdAndStatusNotOrderById(
      String counterpartyId, LoanStatus status);
}
