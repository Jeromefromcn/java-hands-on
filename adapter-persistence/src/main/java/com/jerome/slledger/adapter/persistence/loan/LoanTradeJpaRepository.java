package com.jerome.slledger.adapter.persistence.loan;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface LoanTradeJpaRepository extends JpaRepository<LoanTradeEntity, Long> {

  Optional<LoanTradeEntity> findByIdempotencyKey(String idempotencyKey);
}
