package com.jerome.slledger.adapter.persistence.loan;

import org.springframework.data.jpa.repository.JpaRepository;

interface LoanTradeJpaRepository extends JpaRepository<LoanTradeEntity, Long> {}
