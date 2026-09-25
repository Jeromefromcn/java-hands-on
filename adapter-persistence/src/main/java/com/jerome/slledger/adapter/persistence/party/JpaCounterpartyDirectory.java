package com.jerome.slledger.adapter.persistence.party;

import com.jerome.slledger.application.port.CounterpartyDirectory;
import com.jerome.slledger.domain.party.Counterparty;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JpaCounterpartyDirectory implements CounterpartyDirectory {

  private final CounterpartyJpaRepository jpa;

  JpaCounterpartyDirectory(CounterpartyJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Optional<Counterparty> find(CounterpartyId id) {
    return jpa.findById(id.value()).map(CounterpartyMapper::toDomain);
  }
}
