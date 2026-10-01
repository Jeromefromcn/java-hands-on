package com.jerome.slledger.application.port;

import com.jerome.slledger.domain.party.CounterpartyId;
import java.util.Optional;

/**
 * The paged position listing. A port of its own, not a method on the domain repository: a listing
 * is a read model the use case asks for, not something the aggregate's language has a word for.
 */
public interface PositionListQuery {

  /**
   * Page {@code page} (zero-based) of {@code size} rows, ordered by position id. With a
   * counterparty, only its rows, and the total counts only its rows: the filter is in the {@code
   * WHERE}, so neither a page nor a count can show what it excludes. There is deliberately no
   * overload without the argument; "all" is {@code Optional.empty()}, said out loud.
   */
  PositionPage page(Optional<CounterpartyId> counterparty, int page, int size);
}
