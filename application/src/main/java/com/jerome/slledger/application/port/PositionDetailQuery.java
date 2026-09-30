package com.jerome.slledger.application.port;

import com.jerome.slledger.domain.loan.LoanPositionId;
import java.util.Optional;

/** One position as a read model: the same row the listing shows, looked up by id. */
public interface PositionDetailQuery {

  Optional<PositionRow> find(LoanPositionId id);
}
