package com.jerome.slledger.adapter.persistence.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.SharedRedis;
import com.jerome.slledger.application.cache.Entry;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import tools.jackson.databind.json.JsonMapper;

/** The Redis tier against a real Redis, and the two ways to lose a price on the way there. */
class RedisPriceTierIT {

  private static final String PREFIX = "price:";
  private static final Isin ISIN = new Isin("HK0000000001");

  private SharedRedis.Client redis;
  private RedisPriceTier tier;
  private final PriceCodec codec = new PriceCodec();

  @BeforeAll
  static void start() {
    SharedRedis.start();
  }

  @BeforeEach
  void connect() {
    redis = SharedRedis.client();
    tier = new RedisPriceTier(redis.template(), PREFIX, codec);
  }

  @AfterEach
  void clean() {
    Set<String> keys = redis.template().keys(PREFIX + "*");
    for (String key : keys) {
      assertThat(redis.template().getExpire(key, TimeUnit.MILLISECONDS))
          .as("%s must carry a TTL", key)
          .isPositive();
    }
    SharedRedis.flushAll();
    redis.close();
  }

  @Test
  @Timeout(30)
  void moneyRoundTripsThroughRedis() {
    StringBuilder evidence =
        new StringBuilder(
            """
            Stage 9, section 5: Money through Redis as JSON with a string amount.
            Measured against a real redis:7.4-alpine (Testcontainers), 16 integer digits.

            currency  stored JSON in Redis                                                back
            """);
    for (Money price :
        new Money[] {
          Money.of("1234567890123456.78", Currency.getInstance("HKD")),
          Money.of("1234567890123456", Currency.getInstance("JPY")),
          Money.of("1234567890123456.789", Currency.getInstance("BHD")),
        }) {
      tier.put(ISIN, Entry.present(price), Duration.ofSeconds(30));

      String stored = redis.template().opsForValue().get(PREFIX + ISIN.value());
      Entry<Money> back = tier.get(ISIN).orElseThrow();

      assertThat(back).isEqualTo(Entry.present(price));
      Money read = ((Entry.Present<Money>) back).value();
      assertThat(read.amount()).isEqualByComparingTo(price.amount());
      assertThat(read.amount().scale()).isEqualTo(price.amount().scale());
      assertThat(read.currency()).isEqualTo(price.currency());
      evidence.append(
          "%-9s %-78s %s %s%n"
              .formatted(
                  price.currency().getCurrencyCode(),
                  stored,
                  read.amount().toPlainString(),
                  read.currency()));
    }
    tier.put(ISIN, Entry.absent(), Duration.ofSeconds(30));
    assertThat(tier.get(ISIN)).contains(Entry.absent());
    Evidence.write("09-redis-roundtrip.txt", evidence.toString());
  }

  @Test
  @Timeout(30)
  void undecodableValueIsAMiss() {
    redis.template().opsForValue().set(PREFIX + ISIN.value(), "not json", Duration.ofSeconds(30));
    assertThat(tier.get(ISIN)).isEmpty();

    redis
        .template()
        .opsForValue()
        .set(PREFIX + ISIN.value(), "{\"state\":\"present\"}", Duration.ofSeconds(30));
    assertThat(tier.get(ISIN)).isEmpty();
  }

  @Test
  void overPreciseStoredAmountIsRefused() {
    String overPrecise = "{\"state\":\"present\",\"amount\":\"41.265\",\"currency\":\"HKD\"}";
    String asNumber = "{\"state\":\"present\",\"amount\":41.26,\"currency\":\"HKD\"}";
    String scientific = "{\"state\":\"present\",\"amount\":\"1e3\",\"currency\":\"HKD\"}";
    String unknownCurrency = "{\"state\":\"present\",\"amount\":\"1\",\"currency\":\"XXX\"}";

    for (String json : new String[] {overPrecise, asNumber, scientific, unknownCurrency}) {
      assertThatThrownBy(() -> codec.decodeStrict(json))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(codec.decode(json)).isEmpty();
    }
    assertThat(codec.decode("{\"state\":\"present\",\"amount\":\"41.260\",\"currency\":\"HKD\"}"))
        .contains(Entry.present(Money.of("41.26", Currency.getInstance("HKD"))));
  }

  @Test
  @Timeout(30)
  void everyKeyHasATtl() {
    tier.put(
        ISIN, Entry.present(Money.of("1", Currency.getInstance("HKD"))), Duration.ofSeconds(30));

    assertThat(redis.template().getExpire(PREFIX + ISIN.value(), TimeUnit.MILLISECONDS))
        .isBetween(1L, 30_000L);
  }

  @Test
  void reproducesJdkSerializationRefusesMoney() {
    // See docs/decisions/09-caching.md
    Money price = Money.of("41.26", Currency.getInstance("HKD"));

    assertThatThrownBy(() -> new ObjectOutputStream(new ByteArrayOutputStream()).writeObject(price))
        .isInstanceOf(NotSerializableException.class);
    assertThatThrownBy(() -> new JdkSerializationRedisSerializer().serialize(price))
        .isInstanceOf(SerializationException.class);
  }

  @Test
  void reproducesPrecisionLossWithDoubleCodec() {
    // See docs/decisions/09-caching.md
    JsonMapper mapper = JsonMapper.builder().build();
    Money price = Money.of("12345678901234567.89", Currency.getInstance("HKD"));

    // A codec that writes the amount as a JSON number, read by a reader that parses numbers as
    // doubles, which is what most JSON readers do.
    String asNumber = "{\"amount\":" + price.amount().toPlainString() + "}";
    double parsed = mapper.readTree(asNumber).get("amount").doubleValue();
    BigDecimal lossy = new BigDecimal(parsed);
    String asString = codec.encode(Entry.present(price));
    Money exact = ((Entry.Present<Money>) codec.decodeStrict(asString)).value();

    assertThat(lossy).isNotEqualByComparingTo(price.amount());
    assertThat(exact).isEqualTo(price);
    Evidence.write(
        "09-redis-double-loss.txt",
        """
        Stage 9, section 5: the same amount through a double and through a string.
        Measured on this machine, JDK %s.

        amount stored                : %s HKD
        written as a JSON number,
        read back as a double        : %s
        written as a JSON string,
        read back with PriceCodec    : %s

        The double is off by %s.
        """
            .formatted(
                System.getProperty("java.version"),
                price.amount().toPlainString(),
                lossy.toPlainString(),
                exact.amount().toPlainString(),
                lossy.subtract(price.amount()).abs().toPlainString()));
  }
}
