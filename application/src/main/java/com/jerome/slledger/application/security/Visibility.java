package com.jerome.slledger.application.security;

import com.jerome.slledger.domain.party.CounterpartyId;
import java.util.Objects;
import java.util.Optional;

/**
 * Whose rows a caller may read. Derived from the caller once, at the edge, and handed to every read
 * use case, so a new read cannot forget to scope: it has no way to get the data without one.
 */
public sealed interface Visibility {

  Visibility ALL = new All();
  Visibility NOBODY = new Nobody();

  static Visibility only(CounterpartyId counterparty) {
    return new Only(Objects.requireNonNull(counterparty, "counterparty"));
  }

  /** Whether a row owned by {@code owner} is visible. */
  boolean permits(CounterpartyId owner);

  /**
   * The counterparty a query must filter on, given the one the caller asked for (if any).
   *
   * @throws ForbiddenException if the caller asked for someone it may not see, or may see no one
   */
  Optional<CounterpartyId> narrow(Optional<CounterpartyId> requested);

  record All() implements Visibility {
    @Override
    public boolean permits(CounterpartyId owner) {
      return true;
    }

    @Override
    public Optional<CounterpartyId> narrow(Optional<CounterpartyId> requested) {
      return requested;
    }
  }

  record Only(CounterpartyId counterparty) implements Visibility {
    @Override
    public boolean permits(CounterpartyId owner) {
      return counterparty.equals(owner);
    }

    @Override
    public Optional<CounterpartyId> narrow(Optional<CounterpartyId> requested) {
      if (requested.isPresent() && !requested.get().equals(counterparty)) {
        throw new ForbiddenException("The request names a counterparty outside the caller's scope");
      }
      return Optional.of(counterparty);
    }
  }

  /** A caller with an identity and nothing it may read; fail closed. */
  record Nobody() implements Visibility {
    @Override
    public boolean permits(CounterpartyId owner) {
      return false;
    }

    @Override
    public Optional<CounterpartyId> narrow(Optional<CounterpartyId> requested) {
      throw new ForbiddenException("The caller may not read positions");
    }
  }
}
