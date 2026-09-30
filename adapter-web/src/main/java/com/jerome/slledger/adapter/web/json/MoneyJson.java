package com.jerome.slledger.adapter.web.json;

import com.jerome.slledger.domain.error.UnsupportedCurrencyException;
import com.jerome.slledger.domain.money.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;

/**
 * {@code {"amount": "1234.50", "currency": "HKD"}}.
 *
 * <p>Reading refuses, rather than repairs, anything that would lose or invent information:
 *
 * <ul>
 *   <li>an amount sent as a JSON number, because a client may have rounded it already;
 *   <li>an amount string that is not plain decimal notation ({@code 1e3}, {@code 12,50}, {@code "
 *       1"}), because every extra notation is one more way for two parties to read one value
 *       differently;
 *   <li>an amount with more precision than its currency, because {@link Money}'s constructor would
 *       round it silently. The check is on value, as in stage 6's column reads: {@code 12.500 HKD}
 *       is {@code 12.50 HKD}, and {@code 12.505 HKD} is refused;
 *   <li>an unknown or pseudo currency, and any field other than the two.
 * </ul>
 */
final class MoneyJson {

  static final Pattern PLAIN_DECIMAL = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
  private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");
  private static final Set<String> FIELDS = Set.of("amount", "currency");

  private MoneyJson() {}

  static final class Writer extends ValueSerializer<Money> {

    @Override
    public void serialize(Money value, JsonGenerator gen, SerializationContext ctxt) {
      gen.writeStartObject();
      gen.writeStringProperty("amount", value.amount().toPlainString());
      gen.writeStringProperty("currency", value.currency().getCurrencyCode());
      gen.writeEndObject();
    }
  }

  static final class Reader extends ValueDeserializer<Money> {

    @Override
    public Money deserialize(JsonParser p, DeserializationContext ctxt) {
      JsonNode node = ctxt.readTree(p);
      if (!node.isObject()) {
        return ctxt.reportInputMismatch(
            Money.class, "money must be an object with amount and currency");
      }
      for (String name : node.propertyNames()) {
        if (!FIELDS.contains(name)) {
          return ctxt.reportInputMismatch(Money.class, "money has an unknown field '%s'", name);
        }
      }
      JsonNode amountNode = node.get("amount");
      JsonNode currencyNode = node.get("currency");
      if (amountNode == null || currencyNode == null) {
        return ctxt.reportInputMismatch(Money.class, "money needs both amount and currency");
      }
      if (!amountNode.isString()) {
        return ctxt.reportInputMismatch(
            Money.class,
            "amount must be a JSON string, not a %s: a number may already have been rounded",
            amountNode.getNodeType());
      }
      if (!currencyNode.isString()) {
        return ctxt.reportInputMismatch(Money.class, "currency must be a JSON string");
      }
      String amountText = amountNode.stringValue();
      if (!PLAIN_DECIMAL.matcher(amountText).matches()) {
        return ctxt.reportInputMismatch(
            Money.class, "amount '%s' is not a plain decimal", amountText);
      }
      Currency currency = currencyOf(currencyNode.stringValue(), ctxt);
      BigDecimal sent = new BigDecimal(amountText);
      Money money;
      try {
        money = Money.of(sent, currency);
      } catch (UnsupportedCurrencyException e) {
        return ctxt.reportInputMismatch(
            Money.class, "currency %s has no minor unit", currency.getCurrencyCode());
      }
      if (money.amount().compareTo(sent) != 0) {
        return ctxt.reportInputMismatch(
            Money.class,
            "amount %s has more precision than %s allows; refusing to round it",
            amountText,
            currency.getCurrencyCode());
      }
      return money;
    }

    private static Currency currencyOf(String code, DeserializationContext ctxt) {
      if (!CURRENCY_CODE.matcher(code).matches()) {
        return ctxt.reportInputMismatch(Money.class, "currency '%s' is not an ISO 4217 code", code);
      }
      try {
        return Currency.getInstance(code);
      } catch (IllegalArgumentException e) {
        return ctxt.reportInputMismatch(Money.class, "currency '%s' is not known", code);
      }
    }
  }
}
