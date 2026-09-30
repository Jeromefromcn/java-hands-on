package com.jerome.slledger.adapter.web.quote;

import com.jerome.slledger.adapter.web.validation.ValidIsin;
import com.jerome.slledger.application.quote.QuotePrice;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The live price of a security, fetched from the market data source on every call. The first
 * endpoint whose latency is someone else's: see {@code docs/decisions/07-web.md} for what an
 * unbounded wait here does to the whole server.
 */
@RestController
public class QuoteController {

  private final QuotePrice quote;

  QuoteController(QuotePrice quote) {
    this.quote = quote;
  }

  public record QuoteView(String isin, Money price) {}

  @GetMapping("/securities/{isin}/quote")
  public QuoteView quote(@PathVariable @ValidIsin String isin) {
    return new QuoteView(isin, quote.quote(new Isin(isin)));
  }
}
