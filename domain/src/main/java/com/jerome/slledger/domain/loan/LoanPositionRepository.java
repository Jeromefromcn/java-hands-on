package com.jerome.slledger.domain.loan;

import com.jerome.slledger.domain.party.CounterpartyId;
import java.util.List;
import java.util.Optional;

/**
 * Where positions are kept between transactions: one repository for the one aggregate root.
 *
 * <p>In {@code domain}, beside the aggregate, because it speaks the aggregate's language and
 * nothing else. Everything a use case needs that is not "this position, whole" — a paged listing, a
 * lock strategy, a report — is an {@code application} port, not a method here. Pessimistic and
 * optimistic locking are two implementations of this interface, not two methods on it.
 *
 * <p>Not called {@code LoanPositions}: that name is taken by the summaries over a list of
 * positions.
 *
 * <p>No implementation of this interface is thread-safe or usable outside a transaction; see {@code
 * docs/decisions/06-persistence.md}.
 */
public interface LoanPositionRepository {

  Optional<LoanPosition> find(LoanPositionId id);

  /**
   * This counterparty's positions that are not {@code CLOSED}: what its credit exposure is made of.
   * Returned whole, so that {@link LoanPositions#exposureByCounterparty} does the arithmetic and
   * its currency rule applies; a sum computed in storage would not know the rule.
   */
  List<LoanPosition> activeOf(CounterpartyId counterparty);

  /**
   * Adds a new position, or writes back one that was loaded through this repository in the same
   * unit of work. A change made to a loaded position is not stored until it is passed back here:
   * the aggregate is not the persistence model, so nothing observes it changing.
   */
  void add(LoanPosition position);
}
