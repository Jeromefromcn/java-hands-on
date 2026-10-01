package com.jerome.slledger.application.position;

import com.jerome.slledger.application.port.PositionDetailQuery;
import com.jerome.slledger.application.port.PositionRow;
import com.jerome.slledger.application.security.Visibility;
import com.jerome.slledger.domain.loan.LoanPositionId;
import org.springframework.transaction.annotation.Transactional;

/** One position, read through the projection port, never through the aggregate. */
public class ShowPosition {

  private final PositionDetailQuery query;

  public ShowPosition(PositionDetailQuery query) {
    this.query = query;
  }

  /**
   * @throws UnknownReferenceException if there is no such position, or it is not one the caller may
   *     see: the two are the same answer, so that asking for an id says nothing about whether it
   *     exists
   */
  @Transactional(readOnly = true)
  public PositionRow show(Visibility visibility, LoanPositionId id) {
    return query
        .find(id)
        .filter(row -> visibility.permits(row.counterparty()))
        .orElseThrow(() -> UnknownReferenceException.subject("position", id.value()));
  }
}
