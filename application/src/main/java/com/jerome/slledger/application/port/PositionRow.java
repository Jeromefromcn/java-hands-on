package com.jerome.slledger.application.port;

import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanStatus;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;

/**
 * One line of the position listing: a read model, not the aggregate. It carries what a list shows
 * and nothing that could be written back.
 */
public record PositionRow(
    LoanPositionId id,
    CounterpartyId counterparty,
    Isin security,
    LoanStatus status,
    long originalQuantity,
    long outstandingQuantity,
    Money collateral) {}
