package com.jerome.slledger.adapter.persistence.party;

import com.jerome.slledger.adapter.persistence.support.MoneyColumns;
import com.jerome.slledger.domain.party.Counterparty;
import com.jerome.slledger.domain.party.CounterpartyId;
import com.jerome.slledger.domain.party.CreditLimit;

public final class CounterpartyMapper {

  private CounterpartyMapper() {}

  public static CounterpartyEntity toEntity(Counterparty counterparty) {
    CounterpartyEntity entity = new CounterpartyEntity();
    entity.id = counterparty.id().value();
    entity.name = counterparty.name();
    entity.creditLimitAmount = MoneyColumns.amountOf(counterparty.creditLimit().ceiling());
    entity.creditLimitCurrency = MoneyColumns.currencyOf(counterparty.creditLimit().ceiling());
    return entity;
  }

  public static Counterparty toDomain(CounterpartyEntity entity) {
    return new Counterparty(
        new CounterpartyId(entity.id),
        entity.name,
        new CreditLimit(MoneyColumns.read(entity.creditLimitAmount, entity.creditLimitCurrency)));
  }
}
