package com.jerome.slledger.bootstrap.demo.cache;

/** Another bean: its call to {@link PriceService} goes through the proxy Spring injected. */
public class PriceCaller {

  private final PriceService prices;

  public PriceCaller(PriceService prices) {
    this.prices = prices;
  }

  public String priceTwiceViaProxy(String isin) {
    return prices.priceOf(isin) + "|" + prices.priceOf(isin);
  }
}
