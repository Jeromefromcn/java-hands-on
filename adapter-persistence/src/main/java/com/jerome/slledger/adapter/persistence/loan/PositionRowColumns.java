package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.domain.loan.LoanStatus;
import java.math.BigDecimal;

/**
 * The raw columns of one listing row, as a JPQL constructor expression returns them. Mapped to
 * {@code PositionRow} afterwards, so money still goes through {@code MoneyColumns}.
 */
record PositionRowColumns(
    String id,
    String counterpartyId,
    String isin,
    LoanStatus status,
    long originalQuantity,
    long outstandingQuantity,
    BigDecimal collateralAmount,
    String collateralCurrency) {}
