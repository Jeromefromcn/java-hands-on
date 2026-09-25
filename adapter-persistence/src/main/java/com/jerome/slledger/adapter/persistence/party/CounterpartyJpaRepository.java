package com.jerome.slledger.adapter.persistence.party;

import org.springframework.data.jpa.repository.JpaRepository;

interface CounterpartyJpaRepository extends JpaRepository<CounterpartyEntity, String> {}
