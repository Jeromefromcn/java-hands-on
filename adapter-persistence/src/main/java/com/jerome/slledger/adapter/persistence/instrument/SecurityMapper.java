package com.jerome.slledger.adapter.persistence.instrument;

import com.jerome.slledger.adapter.persistence.support.MoneyColumns;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.instrument.Security;
import java.util.Currency;

/**
 * {@code Security} keeps its currency twice, once on its own and once inside the market price, and
 * refuses to be built when they disagree. The row stores both rather than deriving one from the
 * other, so a row where they disagree is refused on read instead of quietly repaired.
 */
public final class SecurityMapper {

  private SecurityMapper() {}

  public static SecurityEntity toEntity(Security security) {
    SecurityEntity entity = new SecurityEntity();
    entity.isin = security.isin().value();
    entity.name = security.name();
    entity.currency = security.currency().getCurrencyCode();
    entity.marketPriceAmount = MoneyColumns.amountOf(security.marketPrice());
    entity.marketPriceCurrency = MoneyColumns.currencyOf(security.marketPrice());
    return entity;
  }

  public static Security toDomain(SecurityEntity entity) {
    return new Security(
        new Isin(entity.isin.trim()),
        entity.name,
        Currency.getInstance(entity.currency.trim()),
        MoneyColumns.read(entity.marketPriceAmount, entity.marketPriceCurrency));
  }
}
