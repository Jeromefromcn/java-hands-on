package com.jerome.slledger.adapter.web.json;

import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.module.SimpleModule;

/**
 * How money and rates cross JSON, in both directions: inbound request bodies, outbound responses,
 * and what the market data client reads from outside.
 *
 * <p>Amounts and rates are JSON strings, never numbers. Most JSON parsers read a number as an IEEE
 * double, which cannot hold {@code 9999999999999999.99}; by the time such an amount reaches this
 * service it may already have been rounded by the client. See {@code docs/decisions/07-web.md}.
 *
 * <p>A bean, so Boot registers it on the application's {@code JsonMapper}.
 */
@Component
public final class LedgerJacksonModule extends SimpleModule {

  private static final long serialVersionUID = 1L;

  public LedgerJacksonModule() {
    super("sl-ledger");
    addSerializer(Money.class, new MoneyJson.Writer());
    addDeserializer(Money.class, new MoneyJson.Reader());
    addSerializer(Rate.class, new RateJson.Writer());
    addDeserializer(Rate.class, new RateJson.Reader());
  }
}
