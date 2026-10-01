package com.jerome.slledger.adapter.web.quote;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jerome.slledger.adapter.web.error.Problems;
import com.jerome.slledger.adapter.web.validation.ValidIsin;
import com.jerome.slledger.application.position.InvalidQueryException;
import com.jerome.slledger.application.quote.PriceFanOut;
import com.jerome.slledger.application.quote.QuoteOutcome;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prices for several securities in one call, fetched concurrently on a bounded executor.
 *
 * <p>One security failing is that security's item, not the request's failure: the response is
 * {@code 200} with an {@code error} in the item that has no price. Only when the executor refused
 * every security is the request itself a {@code 503}. Prices keep their own currency; nothing here
 * adds them. An item served from the last-known price carries {@code "stale": true} and {@code
 * asOf}; the other items of the same response are unaffected.
 */
@RestController
public class QuotesController {

  private final PriceFanOut fanOut;
  private final int maxIsins;

  QuotesController(PriceFanOut fanOut, @Value("${ledger.quotes.max-isins:20}") int maxIsins) {
    this.fanOut = fanOut;
    this.maxIsins = maxIsins;
  }

  public record QuotesView(List<QuoteItem> quotes) {}

  /** Exactly one of {@code price} and {@code error} is present. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record QuoteItem(String isin, Money price, Boolean stale, Instant asOf, ItemError error) {}

  public record ItemError(String type) {}

  @GetMapping("/quotes")
  public QuotesView quotes(@RequestParam("isin") @NotEmpty List<@ValidIsin String> isins) {
    if (isins.size() > maxIsins) {
      throw new InvalidQueryException(
          "At most %d securities per request, got %d".formatted(maxIsins, isins.size()));
    }
    List<QuoteItem> items =
        fanOut.quote(isins.stream().map(Isin::new).toList()).stream()
            .map(QuotesController::item)
            .toList();
    return new QuotesView(items);
  }

  private static QuoteItem item(QuoteOutcome outcome) {
    return switch (outcome) {
      case QuoteOutcome.Priced priced ->
          new QuoteItem(
              priced.isin().value(),
              priced.price(),
              priced.stale() ? true : null,
              priced.asOf(),
              null);
      case QuoteOutcome.Failed failed ->
          new QuoteItem(
              failed.isin().value(),
              null,
              null,
              null,
              new ItemError(Problems.type(kindOf(failed.kind())).toString()));
    };
  }

  private static String kindOf(QuoteOutcome.Kind kind) {
    return switch (kind) {
      case UNAVAILABLE -> "market-data-unavailable";
      case REJECTED -> "market-data-rejected";
      case UNKNOWN -> "not-found";
      case TIMED_OUT -> "market-data-timeout";
      case OVERLOADED -> "overloaded";
      case ERROR -> "internal-error";
    };
  }
}
