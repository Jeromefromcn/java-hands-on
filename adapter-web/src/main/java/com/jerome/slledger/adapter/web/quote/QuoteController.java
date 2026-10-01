package com.jerome.slledger.adapter.web.quote;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jerome.slledger.adapter.web.validation.ValidIsin;
import com.jerome.slledger.application.quote.Quote;
import com.jerome.slledger.application.quote.QuotePrice;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The live price of a security, fetched from the market data source on every call. The first
 * endpoint whose latency is someone else's: see {@code docs/decisions/07-web.md} for what an
 * unbounded wait here does to the whole server.
 *
 * <p>When the source cannot answer and a recent last-known price exists, that price is returned
 * with {@code "stale": true} and the time it was true, and a {@code Warning: 110} header. A live
 * price carries neither field: its absence is the normal case, and a client has to opt in to
 * trusting a stale one by reading the flag.
 */
@RestController
public class QuoteController {

  /** The standard "Response is Stale" warning (RFC 9111, 5.5.1). */
  static final String STALE_WARNING = "110 - \"Response is Stale\"";

  private final QuotePrice quote;

  QuoteController(QuotePrice quote) {
    this.quote = quote;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record QuoteView(String isin, Money price, Boolean stale, Instant asOf) {

    static QuoteView of(String isin, Quote quote) {
      return quote.stale()
          ? new QuoteView(isin, quote.price(), true, quote.asOf())
          : new QuoteView(isin, quote.price(), null, null);
    }
  }

  @GetMapping("/securities/{isin}/quote")
  public ResponseEntity<QuoteView> quote(@PathVariable @ValidIsin String isin) {
    Quote quoted = quote.quote(new Isin(isin));
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (quoted.stale()) {
      response.header("Warning", STALE_WARNING);
    }
    return response.body(QuoteView.of(isin, quoted));
  }
}
