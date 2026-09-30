package com.jerome.slledger.application.position;

import com.jerome.slledger.application.port.PositionDetailQuery;
import com.jerome.slledger.application.port.PositionRow;
import com.jerome.slledger.domain.loan.LoanPositionId;
import org.springframework.transaction.annotation.Transactional;

/** One position, read through the projection port, never through the aggregate. */
public class ShowPosition {

  private final PositionDetailQuery query;

  public ShowPosition(PositionDetailQuery query) {
    this.query = query;
  }

  /**
   * @throws UnknownReferenceException if there is no such position
   */
  @Transactional(readOnly = true)
  public PositionRow show(LoanPositionId id) {
    return query.find(id).orElseThrow(() -> new UnknownReferenceException("position", id.value()));
  }
}
