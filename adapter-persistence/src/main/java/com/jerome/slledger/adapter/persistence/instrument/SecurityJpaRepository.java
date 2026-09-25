package com.jerome.slledger.adapter.persistence.instrument;

import org.springframework.data.jpa.repository.JpaRepository;

interface SecurityJpaRepository extends JpaRepository<SecurityEntity, String> {}
