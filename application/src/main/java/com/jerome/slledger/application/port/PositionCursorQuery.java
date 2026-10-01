package com.jerome.slledger.application.port;

import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.util.Optional;

/**
 * The position listing by keyset: the rows after a given id, in id order. No total and no page
 * number, so neither a count nor an offset scan is paid for, and rows inserted or removed ahead of
 * the cursor do not shift what the next slice returns.
 */
public interface PositionCursorQuery {

  /**
   * Up to {@code size} rows with an id after {@code after}, or from the start if it is empty; of
   * one counterparty if given. As {@link PositionListQuery}, there is no overload without it.
   */
  PositionSlice after(
      Optional<CounterpartyId> counterparty, Optional<LoanPositionId> after, int size);
}
