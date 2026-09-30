package com.jerome.slledger.application.messaging;

import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPosition;
import java.util.List;

/** Read access to the positions that a price movement can put short of collateral. */
public interface SecurityPositions {

  /** Every position in {@code security} that is not closed. Read only; nothing here is changed. */
  List<LoanPosition> openIn(Isin security);
}
