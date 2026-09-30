package com.jerome.slledger.adapter.web.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

class LedgerJacksonModuleTest {

  private final JsonMapper mapper =
      JsonMapper.builder().addModule(new LedgerJacksonModule()).build();

  private record Holder(Money money) {}

  private record RateHolder(Rate rate) {}

  private record DateHolder(LocalDate date) {}

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
    "9999999999999999.99, HKD, 2",
    "9999999999999999, JPY, 0",
    "9999999999999999.999, BHD, 3",
    "-0.01, HKD, 2"
  })
  @DisplayName("money round-trips with amount, scale and currency intact")
  void roundTrips(String amount, String currency, int scale) {
    Money money = Money.of(amount, Currency.getInstance(currency));

    String json = mapper.writeValueAsString(new Holder(money));
    Money back = mapper.readValue(json, Holder.class).money();

    assertThat(json)
        .isEqualTo(
            "{\"money\":{\"amount\":\"%s\",\"currency\":\"%s\"}}".formatted(amount, currency));
    assertThat(back).isEqualTo(money);
    assertThat(back.amount().scale()).isEqualTo(scale);
    assertThat(back.amount().toPlainString()).isEqualTo(amount);
  }

  @Test
  @DisplayName("an amount is written in plain notation, never scientific")
  void writesPlainNotation() {
    Money money = Money.of(new BigDecimal("1E+3"), Currency.getInstance("JPY"));

    assertThat(mapper.writeValueAsString(money))
        .isEqualTo("{\"amount\":\"1000\",\"currency\":\"JPY\"}");
  }

  @Test
  @DisplayName("trailing zeros past the currency's scale lose nothing, so they are accepted")
  void acceptsTrailingZerosPastScale() {
    Money money = read("{\"amount\":\"12.500\",\"currency\":\"HKD\"}");

    assertThat(money).isEqualTo(Money.of("12.50", Currency.getInstance("HKD")));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "{\"amount\":12.50,\"currency\":\"HKD\"}",
        "{\"amount\":12,\"currency\":\"JPY\"}",
        "{\"amount\":\"12,50\",\"currency\":\"HKD\"}",
        "{\"amount\":\"1e3\",\"currency\":\"JPY\"}",
        "{\"amount\":\" 1\",\"currency\":\"JPY\"}",
        "{\"amount\":\"+1\",\"currency\":\"JPY\"}",
        "{\"amount\":\".5\",\"currency\":\"HKD\"}",
        "{\"amount\":\"\",\"currency\":\"HKD\"}",
        "{\"amount\":\"12.505\",\"currency\":\"HKD\"}",
        "{\"amount\":\"1.5\",\"currency\":\"JPY\"}",
        "{\"amount\":\"1.0005\",\"currency\":\"BHD\"}",
        "{\"amount\":\"1.00\",\"currency\":\"hkd\"}",
        "{\"amount\":\"1.00\",\"currency\":\"ABC\"}",
        "{\"amount\":\"1.00\",\"currency\":\"XXX\"}",
        "{\"amount\":\"1.00\",\"currency\":840}",
        "{\"amount\":\"1.00\"}",
        "{\"currency\":\"HKD\"}",
        "{\"amount\":\"1.00\",\"currency\":\"HKD\",\"rounded\":true}",
        "\"1.00 HKD\"",
        "12.5"
      })
  @DisplayName("anything that would lose or invent information is refused")
  void refuses(String json) {
    assertThatThrownBy(() -> read(json)).isInstanceOf(MismatchedInputException.class);
  }

  @Test
  @DisplayName("a rate is a string, unscaled, both ways")
  void rateRoundTrips() {
    String json = mapper.writeValueAsString(new RateHolder(Rate.of("0.0400")));

    assertThat(json).isEqualTo("{\"rate\":\"0.0400\"}");
    assertThat(mapper.readValue(json, RateHolder.class).rate()).isEqualTo(Rate.of("0.0400"));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"{\"rate\":0.04}", "{\"rate\":\"-0.01\"}", "{\"rate\":\"4%\"}"})
  @DisplayName("a rate as a number, negative, or not a plain decimal is refused")
  void refusesRate(String json) {
    assertThatThrownBy(() -> mapper.readValue(json, RateHolder.class))
        .isInstanceOf(MismatchedInputException.class);
  }

  @Test
  @DisplayName("LocalDate is ISO yyyy-MM-dd both ways")
  void isoDates() {
    String json = mapper.writeValueAsString(new DateHolder(LocalDate.of(2026, 9, 30)));

    assertThat(json).isEqualTo("{\"date\":\"2026-09-30\"}");
    assertThat(mapper.readValue(json, DateHolder.class).date())
        .isEqualTo(LocalDate.of(2026, 9, 30));
  }

  private Money read(String json) {
    return mapper.readValue(json, Money.class);
  }
}
