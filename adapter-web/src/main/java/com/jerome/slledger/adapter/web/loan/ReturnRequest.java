package com.jerome.slledger.adapter.web.loan;

import com.jerome.slledger.application.position.ReturnCommand;
import com.jerome.slledger.domain.loan.IdempotencyKey;
import com.jerome.slledger.domain.loan.LoanPositionId;
import java.time.LocalDate;

/**
 * The body of {@code POST /loans/{id}/returns}: how many to return, not the new balance. A return
 * is a delta appended to the position, which is why it is a {@code POST} of a new return and not a
 * {@code PATCH} of the position. See {@code docs/decisions/07-web.md}.
 */
public record ReturnRequest(Long quantity, LocalDate tradeDate) {

  ReturnCommand toCommand(String idempotencyKey, String positionId) {
    return new ReturnCommand(
        new IdempotencyKey(idempotencyKey), new LoanPositionId(positionId), quantity, tradeDate);
  }
}
