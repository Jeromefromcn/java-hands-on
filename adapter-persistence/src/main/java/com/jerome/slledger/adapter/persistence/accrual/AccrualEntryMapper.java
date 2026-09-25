package com.jerome.slledger.adapter.persistence.accrual;

import com.jerome.slledger.adapter.persistence.support.MoneyColumns;
import com.jerome.slledger.domain.accrual.AccrualEntry;
import com.jerome.slledger.domain.loan.LoanPositionId;

public final class AccrualEntryMapper {

  private AccrualEntryMapper() {}

  public static AccrualEntryEntity toEntity(AccrualEntry entry) {
    AccrualEntryEntity entity = new AccrualEntryEntity();
    entity.positionId = entry.position().value();
    entity.businessDate = entry.accruedOn();
    entity.feeAmount = MoneyColumns.amountOf(entry.fee());
    entity.feeCurrency = MoneyColumns.currencyOf(entry.fee());
    return entity;
  }

  public static AccrualEntry toDomain(AccrualEntryEntity entity) {
    return new AccrualEntry(
        new LoanPositionId(entity.positionId),
        entity.businessDate,
        MoneyColumns.read(entity.feeAmount, entity.feeCurrency));
  }
}
