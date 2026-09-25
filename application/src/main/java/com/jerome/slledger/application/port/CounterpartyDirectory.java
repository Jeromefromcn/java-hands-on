package com.jerome.slledger.application.port;

import com.jerome.slledger.domain.party.Counterparty;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.util.Optional;

/** Reference data about borrowers. Read-only from the use cases' side. */
public interface CounterpartyDirectory {

  Optional<Counterparty> find(CounterpartyId id);
}
