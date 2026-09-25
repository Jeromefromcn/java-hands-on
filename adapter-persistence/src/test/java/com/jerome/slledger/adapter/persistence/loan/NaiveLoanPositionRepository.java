package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanPositionRepository;
import com.jerome.slledger.domain.party.CounterpartyId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;

/**
 * The common shape this project rejected: every save loads the row as it is <em>now</em> and copies
 * the aggregate onto it. The version check then compares the version just read against itself,
 * which always passes. Test-only; see docs/decisions/06-persistence.md.
 */
class NaiveLoanPositionRepository implements LoanPositionRepository {

  @PersistenceContext private EntityManager em;

  @Override
  public Optional<LoanPosition> find(LoanPositionId id) {
    return Optional.ofNullable(em.find(LoanPositionEntity.class, id.value()))
        .map(LoanPositionMapper::toDomain);
  }

  @Override
  public List<LoanPosition> activeOf(CounterpartyId counterparty) {
    throw new UnsupportedOperationException("not needed by the reproduction");
  }

  @Override
  public void add(LoanPosition position) {
    LoanPositionEntity current = em.find(LoanPositionEntity.class, position.id().value());
    if (current == null) {
      em.persist(LoanPositionMapper.toEntity(position));
    } else {
      LoanPositionMapper.copyMutableState(position, current);
    }
  }
}
