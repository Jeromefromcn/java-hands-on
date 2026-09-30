package com.jerome.slledger.adapter.messaging;

import com.jerome.slledger.application.messaging.EventCodec;
import com.jerome.slledger.application.messaging.IntegrationEvent;
import com.jerome.slledger.application.messaging.IntegrationEvent.MarginCallRaised;
import com.jerome.slledger.application.messaging.IntegrationEvent.PositionOpened;
import com.jerome.slledger.application.messaging.IntegrationEvent.PriceUpdated;
import com.jerome.slledger.application.messaging.MessageDecodeException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The events as JSON, and the money invariant at the fourth boundary.
 *
 * <p>A {@code Money} is written as {@code {"amount":"41.30","currency":"HKD"}}: the amount a JSON
 * <em>string</em>, because a number is a {@code double} to most readers and a {@code double} cannot
 * hold a 16-digit amount. Reading is strict, by the same rules as stage 7's HTTP boundary and stage
 * 9's Redis one: plain decimal notation only; an amount with more decimals than its currency has is
 * refused rather than rounded by {@code Money}'s constructor; an unknown currency is refused. A
 * field the type does not have is refused too, so a producer that adds one is loud on the first
 * message and not silent forever.
 *
 * <p>Every refusal is a {@link MessageDecodeException}, which is not retried: reading the same
 * bytes again reads them the same way.
 */
public final class JsonEventCodec implements EventCodec {

  private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
  private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");

  private static final Set<String> PRICE_FIELDS =
      Set.of("eventId", "occurredAt", "isin", "price", "version");
  private static final Set<String> MARGIN_CALL_FIELDS =
      Set.of("eventId", "occurredAt", "position", "counterparty", "isin", "calledOn", "shortfall");
  private static final Set<String> OPENED_FIELDS =
      Set.of("eventId", "occurredAt", "position", "counterparty", "isin", "quantity", "collateral");

  private final ObjectMapper mapper = JsonMapper.builder().build();

  @Override
  public String encode(IntegrationEvent event) {
    ObjectNode node = mapper.createObjectNode();
    node.put("eventId", event.id().toString());
    node.put("occurredAt", event.occurredAt().toString());
    switch (event) {
      case PriceUpdated e -> {
        node.put("isin", e.security().value());
        node.set("price", money(e.price()));
        node.put("version", e.version());
      }
      case MarginCallRaised e -> {
        node.put("position", e.position().value());
        node.put("counterparty", e.counterparty().value());
        node.put("isin", e.security().value());
        node.put("calledOn", e.calledOn().toString());
        node.set("shortfall", money(e.shortfall()));
      }
      case PositionOpened e -> {
        node.put("position", e.position().value());
        node.put("counterparty", e.counterparty().value());
        node.put("isin", e.security().value());
        node.put("quantity", e.quantity());
        node.set("collateral", money(e.collateral()));
      }
    }
    return node.toString();
  }

  @Override
  public IntegrationEvent decode(String type, String payload) {
    try {
      JsonNode node = mapper.readTree(payload);
      if (!node.isObject()) {
        throw new MessageDecodeException("payload is not a JSON object");
      }
      return switch (type) {
        case PriceUpdated.TYPE -> {
          onlyFields(node, PRICE_FIELDS);
          yield new PriceUpdated(
              uuid(node, "eventId"),
              instant(node, "occurredAt"),
              new Isin(text(node, "isin")),
              money(node, "price"),
              wholeNumber(node, "version"));
        }
        case MarginCallRaised.TYPE -> {
          onlyFields(node, MARGIN_CALL_FIELDS);
          yield new MarginCallRaised(
              uuid(node, "eventId"),
              instant(node, "occurredAt"),
              new LoanPositionId(text(node, "position")),
              new CounterpartyId(text(node, "counterparty")),
              new Isin(text(node, "isin")),
              LocalDate.parse(text(node, "calledOn")),
              money(node, "shortfall"));
        }
        case PositionOpened.TYPE -> {
          onlyFields(node, OPENED_FIELDS);
          yield new PositionOpened(
              uuid(node, "eventId"),
              instant(node, "occurredAt"),
              new LoanPositionId(text(node, "position")),
              new CounterpartyId(text(node, "counterparty")),
              new Isin(text(node, "isin")),
              wholeNumber(node, "quantity"),
              money(node, "collateral"));
        }
        default -> throw new MessageDecodeException("unknown event type '" + type + "'");
      };
    } catch (MessageDecodeException e) {
      throw e;
    } catch (RuntimeException e) {
      // IllegalArgumentException, DateTimeParseException, the domain's own refusals, malformed
      // JSON: all of them mean the bytes are not an event, and none of them will read differently
      // on a second attempt.
      throw new MessageDecodeException("cannot decode a " + type + ": " + e.getMessage(), e);
    }
  }

  private ObjectNode money(Money money) {
    ObjectNode node = mapper.createObjectNode();
    node.put("amount", money.amount().toPlainString());
    node.put("currency", money.currency().getCurrencyCode());
    return node;
  }

  private static void onlyFields(JsonNode node, Set<String> allowed) {
    for (String name : node.propertyNames()) {
      if (!allowed.contains(name)) {
        throw new MessageDecodeException("unexpected field '" + name + "'");
      }
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString()) {
      throw new MessageDecodeException("'" + field + "' must be a string");
    }
    return value.stringValue();
  }

  private static UUID uuid(JsonNode node, String field) {
    return UUID.fromString(text(node, field));
  }

  private static Instant instant(JsonNode node, String field) {
    return Instant.parse(text(node, field));
  }

  private static long wholeNumber(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new MessageDecodeException("'" + field + "' must be a whole number");
    }
    return value.longValue();
  }

  private static Money money(JsonNode parent, String field) {
    JsonNode node = parent.get(field);
    if (node == null || !node.isObject() || node.size() != 2) {
      throw new MessageDecodeException("'" + field + "' must be an amount and a currency");
    }
    String amountText = text(node, "amount");
    String code = text(node, "currency");
    if (!PLAIN_DECIMAL.matcher(amountText).matches() || !CURRENCY_CODE.matcher(code).matches()) {
      throw new MessageDecodeException("'" + field + "' is not in plain notation");
    }
    BigDecimal stated = new BigDecimal(amountText);
    Money money = Money.of(stated, Currency.getInstance(code));
    if (money.amount().compareTo(stated) != 0) {
      throw new MessageDecodeException(
          "'" + field + "' has more precision than " + code + " allows");
    }
    return money;
  }
}
