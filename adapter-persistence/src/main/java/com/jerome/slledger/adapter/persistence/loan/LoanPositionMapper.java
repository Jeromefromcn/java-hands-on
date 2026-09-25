package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.adapter.persistence.support.MoneyColumns;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.party.CounterpartyId;

/**
 * Positions come back only through {@link LoanPosition#restore}, so a stored row is held to the
 * same invariant as a position built in memory.
 */
public final class LoanPositionMapper {

  private LoanPositionMapper() {}

  public static LoanPositionEntity toEntity(LoanPosition position) {
    LoanPositionEntity entity = new LoanPositionEntity();
    entity.id = position.id().value();
    entity.counterpartyId = position.counterparty().value();
    entity.isin = position.security().value();
    entity.originalQuantity = position.originalQuantity();
    entity.collateralAmount = MoneyColumns.amountOf(position.collateral());
    entity.collateralCurrency = MoneyColumns.currencyOf(position.collateral());
    entity.ruleType = AccrualRuleColumns.typeOf(position.accrualRule());
    entity.flatRate = AccrualRuleColumns.flatRateOf(position.accrualRule());
    copyMutableState(position, entity);
    return entity;
  }

  /** The two fields a position can change after it is opened; everything else is fixed. */
  public static void copyMutableState(LoanPosition position, LoanPositionEntity entity) {
    entity.outstandingQuantity = position.outstandingQuantity();
    entity.status = position.status();
  }

  public static LoanPosition toDomain(LoanPositionEntity entity) {
    return LoanPosition.restore(
        new LoanPositionId(entity.id),
        new CounterpartyId(entity.counterpartyId),
        new Isin(entity.isin.trim()),
        entity.originalQuantity,
        entity.outstandingQuantity,
        entity.status,
        MoneyColumns.read(entity.collateralAmount, entity.collateralCurrency),
        AccrualRuleColumns.toRule(entity.ruleType, entity.flatRate));
  }
}
