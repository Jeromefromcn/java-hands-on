package com.jerome.slledger.adapter.web.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.web.testing.Evidence;
import com.jerome.slledger.domain.money.Money;
import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The precision loss that makes amounts JSON strings. See docs/decisions/07-web.md. */
class JsonPrecisionTest {

  private static final Money AMOUNT = Money.of("9999999999999999.99", Currency.getInstance("HKD"));

  @Test
  @DisplayName("an amount written as a JSON number and read as a double is no longer the amount")
  void reproducesPrecisionLossAsJsonNumber() {
    // See docs/decisions/07-web.md
    JsonMapper withoutModule = JsonMapper.builder().build();

    String json = withoutModule.writeValueAsString(AMOUNT);
    // What a JavaScript client's JSON.parse does with a number: an IEEE double.
    JsonNode parsed = withoutModule.readTree(json);
    double asDouble = parsed.get("amount").doubleValue();
    BigDecimal readBack = new BigDecimal(asDouble);

    assertThat(json).contains("\"amount\":9999999999999999.99");
    assertThat(readBack).isNotEqualByComparingTo(AMOUNT.amount());

    JsonMapper withModule = JsonMapper.builder().addModule(new LedgerJacksonModule()).build();
    String fixed = withModule.writeValueAsString(AMOUNT);
    Money fixedBack = withModule.readValue(fixed, Money.class);
    assertThat(fixedBack).isEqualTo(AMOUNT);

    Evidence.write(
        "07-json-precision.txt",
        """
        Stage 07 · section 2 — an amount as a JSON number
        (JsonPrecisionTest.reproducesPrecisionLossAsJsonNumber)

        amount sent                  %s %s
        JSON without the module      %s
        read back as a double        %s
        as BigDecimal(double)        %s
        difference                   %s

        JSON with LedgerJacksonModule  %s
        read back                      %s %s (equal: %s)
        """
            .formatted(
                AMOUNT.amount().toPlainString(),
                AMOUNT.currency(),
                json,
                asDouble,
                readBack.toPlainString(),
                readBack.subtract(AMOUNT.amount()).toPlainString(),
                fixed,
                fixedBack.amount().toPlainString(),
                fixedBack.currency(),
                fixedBack.equals(AMOUNT)));
  }
}
