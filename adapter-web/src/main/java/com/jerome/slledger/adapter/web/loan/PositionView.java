package com.jerome.slledger.adapter.web.loan;

import com.jerome.slledger.application.port.PositionRow;
import com.jerome.slledger.domain.loan.LoanStatus;
import com.jerome.slledger.domain.money.Money;

/**
 * A position as the API shows it. Identifiers are flattened to strings here: the domain's value
 * records would otherwise serialize as {@code {"value": "..."}} objects, and the wire format would
 * follow whatever the domain's types happen to look like.
 */
public record PositionView(
    String id,
    String counterpartyId,
    String isin,
    LoanStatus status,
    long originalQuantity,
    long outstandingQuantity,
    Money collateral) {

  static PositionView of(PositionRow row) {
    return new PositionView(
        row.id().value(),
        row.counterparty().value(),
        row.security().value(),
        row.status(),
        row.originalQuantity(),
        row.outstandingQuantity(),
        row.collateral());
  }
}
