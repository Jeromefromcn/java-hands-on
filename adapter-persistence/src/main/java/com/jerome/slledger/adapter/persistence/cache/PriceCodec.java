package com.jerome.slledger.adapter.persistence.cache;

import com.jerome.slledger.application.cache.Entry;
import com.jerome.slledger.domain.error.UnsupportedCurrencyException;
import com.jerome.slledger.domain.money.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a price looks like inside Redis: {@code {"state":"present","amount":"41.26",
 * "currency":"HKD"}}, or {@code {"state":"absent"}}.
 *
 * <p>The amount is a JSON <em>string</em>, for the reason the HTTP boundary has: a number is a
 * {@code double} to most readers, and a {@code double} cannot hold a 17-digit amount. Reading
 * follows stage 7's rules. It refuses an amount that is not plain decimal notation, an amount with
 * more decimals than its currency has (that would be rounded by {@code Money}'s constructor,
 * silently), and an unknown currency. A refusal is an {@link IllegalArgumentException}; {@link
 * #decode} turns it into "no entry", so a value nobody can trust is reloaded rather than served.
 */
public final class PriceCodec {

  private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
  private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");

  private final ObjectMapper mapper = JsonMapper.builder().build();

  public String encode(Entry<Money> entry) {
    ObjectNode node = mapper.createObjectNode();
    switch (entry) {
      case Entry.Present<Money> present -> {
        node.put("state", "present");
        node.put("amount", present.value().amount().toPlainString());
        node.put("currency", present.value().currency().getCurrencyCode());
      }
      case Entry.Absent<Money> absent -> node.put("state", "absent");
    }
    return node.toString();
  }

  /** The entry, or empty for anything that is not exactly what {@link #encode} writes. */
  public Optional<Entry<Money>> decode(String json) {
    try {
      return Optional.of(decodeStrict(json));
    } catch (IllegalArgumentException | tools.jackson.core.JacksonException e) {
      return Optional.empty();
    }
  }

  public Entry<Money> decodeStrict(String json) {
    JsonNode node = mapper.readTree(json);
    if (!node.isObject() || !node.path("state").isString()) {
      throw new IllegalArgumentException("not a cache entry");
    }
    return switch (node.get("state").stringValue()) {
      case "absent" -> {
        if (node.size() != 1) {
          throw new IllegalArgumentException("an absent entry carries nothing else");
        }
        yield Entry.absent();
      }
      case "present" -> Entry.present(money(node));
      default -> throw new IllegalArgumentException("unknown state");
    };
  }

  private static Money money(JsonNode node) {
    if (node.size() != 3 || !node.path("amount").isString() || !node.path("currency").isString()) {
      throw new IllegalArgumentException("a present entry has a string amount and a currency");
    }
    String amountText = node.get("amount").stringValue();
    String code = node.get("currency").stringValue();
    if (!PLAIN_DECIMAL.matcher(amountText).matches() || !CURRENCY_CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("amount or currency is not in plain notation");
    }
    BigDecimal stored = new BigDecimal(amountText);
    Money money;
    try {
      money = Money.of(stored, Currency.getInstance(code));
    } catch (UnsupportedCurrencyException e) {
      throw new IllegalArgumentException("currency has no minor unit", e);
    }
    if (money.amount().compareTo(stored) != 0) {
      throw new IllegalArgumentException("amount has more precision than its currency allows");
    }
    return money;
  }
}
