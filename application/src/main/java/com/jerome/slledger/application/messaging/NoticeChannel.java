package com.jerome.slledger.application.messaging;

import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.LocalDate;

/**
 * How a counterparty is told that it owes more collateral: an e-mail, a message to its operations
 * desk. An external call, so it cannot be made idempotent by a database constraint, which is why
 * its caller is guarded by a table.
 */
public interface NoticeChannel {

  void send(MarginCallNotice notice);

  record MarginCallNotice(
      CounterpartyId counterparty,
      LoanPositionId position,
      Isin security,
      LocalDate calledOn,
      Money shortfall) {}
}
