package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanPositionRepository;
import com.jerome.slledger.domain.loan.LoanStatus;
import com.jerome.slledger.domain.party.CounterpartyId;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Positions with optimistic locking: no lock is taken on read, and a concurrent write is detected
 * at flush by the {@code version} column.
 *
 * <p>The version lives only on the entity; {@code domain} never sees it. That works because the
 * entity a position was read from stays managed for the rest of the transaction, and {@link #add}
 * copies the position's state back onto that same entity, carrying the version it was read at. It
 * stops working the moment the two are separated, which is why {@link UnitOfWorkPositions} refuses
 * a position this transaction did not load. See {@code docs/decisions/06-persistence.md}.
 */
@Repository
public class OptimisticLoanPositionRepository implements LoanPositionRepository {

  private final LoanPositionJpaRepository jpa;
  private final EntityManager entityManager;
  private final UnitOfWorkPositions unitOfWork;

  public OptimisticLoanPositionRepository(
      LoanPositionJpaRepository jpa, EntityManager entityManager, UnitOfWorkPositions unitOfWork) {
    this.jpa = jpa;
    this.entityManager = entityManager;
    this.unitOfWork = unitOfWork;
  }

  @Override
  public Optional<LoanPosition> find(LoanPositionId id) {
    Map<LoanPosition, LoanPositionEntity> loaded = unitOfWork.current();
    return jpa.findById(id.value()).map(entity -> register(loaded, entity));
  }

  @Override
  public List<LoanPosition> activeOf(CounterpartyId counterparty) {
    Map<LoanPosition, LoanPositionEntity> loaded = unitOfWork.current();
    return jpa
        .findByCounterpartyIdAndStatusNotOrderById(counterparty.value(), LoanStatus.CLOSED)
        .stream()
        .map(entity -> register(loaded, entity))
        .toList();
  }

  @Override
  public void add(LoanPosition position) {
    Map<LoanPosition, LoanPositionEntity> loaded = unitOfWork.current();
    LoanPositionEntity managed = loaded.get(position);
    if (managed != null) {
      LoanPositionMapper.copyMutableState(position, managed);
      return;
    }
    if (jpa.existsById(position.id().value())) {
      throw new PositionNotLoadedInThisTransactionException(position.id().value());
    }
    // persist, not Spring Data's save: save would merge an entity with an assigned id, which
    // costs a SELECT and would quietly update a row this check just said does not exist.
    LoanPositionEntity entity = LoanPositionMapper.toEntity(position);
    entityManager.persist(entity);
    loaded.put(position, entity);
  }

  private static LoanPosition register(
      Map<LoanPosition, LoanPositionEntity> loaded, LoanPositionEntity entity) {
    LoanPosition position = LoanPositionMapper.toDomain(entity);
    loaded.put(position, entity);
    return position;
  }
}
