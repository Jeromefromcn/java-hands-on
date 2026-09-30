package com.jerome.slledger.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.SharedPostgres;
import com.jerome.slledger.bootstrap.SlLedgerApplication;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Starts the real application four ways and reads back what {@code accrual.rounding-precision}
 * resolved to. Each layer is given a different number (2, 4, 6, 8), so a wrong precedence shows up
 * as a wrong number rather than an accidental pass.
 */
class ConfigurationPrecedenceIT {

  private static final String KEY = "accrual.rounding-precision";

  @Test
  @DisplayName("with no profile, the base application.yml value is used")
  void baseYaml() {
    try (var context = start(false)) {
      assertResolved(context, 2);
    }
  }

  @Test
  @DisplayName("an active profile's YAML outranks the base YAML")
  void profileYamlOverBase() {
    try (var context = start(false, "--spring.profiles.active=local")) {
      assertResolved(context, 4);
    }
  }

  @Test
  @DisplayName("an environment variable outranks the profile's YAML")
  void environmentVariableOverProfileYaml() {
    try (var context = start(true, "--spring.profiles.active=local")) {
      assertResolved(context, 6);
    }
  }

  @Test
  @DisplayName("a command-line argument outranks the environment variable")
  void commandLineOverEnvironmentVariable() {
    try (var context = start(true, "--spring.profiles.active=local", "--" + KEY + "=8")) {
      assertResolved(context, 8);
    }
  }

  @ParameterizedTest(name = "profile {0} starts with marketdata source bean {1}")
  @CsvSource({"local, mockReferenceRates", "test, mockReferenceRates", "prod, realReferenceRates"})
  @DisplayName("each profile's YAML also drives which market-data source is wired")
  void profileSelectsMarketDataSource(String profile, String expectedBean) {
    try (var context = start(false, "--spring.profiles.active=" + profile)) {
      assertThat(context.containsBean(expectedBean)).isTrue();
    }
  }

  private static void assertResolved(ConfigurableApplicationContext context, int expected) {
    assertThat(context.getEnvironment().getProperty(KEY, Integer.class)).isEqualTo(expected);
    // Resolution, then binding: the validated bean sees the same winner.
    assertThat(context.getBean(AccrualRuleProperties.class).roundingPrecision())
        .isEqualTo(expected);
  }

  private static ConfigurableApplicationContext start(boolean withEnvVar, String... args) {
    SpringApplicationBuilder builder =
        new SpringApplicationBuilder(SlLedgerApplication.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF);
    if (withEnvVar) {
      // A running JVM cannot set its own environment variables. Instead the systemEnvironment
      // property source is swapped for one holding the real environment plus one variable: same
      // name, same position, so it ranks exactly where a real variable would. Adding it with
      // addFirst instead would put it above the command line and prove the wrong order.
      builder.initializers(
          context -> {
            Map<String, Object> env = new HashMap<>(System.getenv());
            env.put("ACCRUAL_ROUNDINGPRECISION", "6");
            context
                .getEnvironment()
                .getPropertySources()
                .replace(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
          });
    }
    // Since stage 6 the application needs a database. The datasource is appended as arguments,
    // the highest-precedence source, so no profile's YAML can override it; the property this test
    // measures is a different key, so its precedence is unaffected.
    // Since stage 7 the prod profile takes the market data URL from LEDGER_MARKETDATA_URL with no
    // default; a placeholder address stands in for it. Nothing here calls the source.
    // Since stage 9 the local profile turns the quote cache on, which needs a Redis; this test is
    // about which source wins, not about the cache, so the cache is switched off.
    String[] withDatabase =
        Stream.of(
                Arrays.stream(args),
                Arrays.stream(SharedPostgres.freshDatabase()).map(p -> "--" + p),
                Stream.of(
                    "--ledger.marketdata.base-url=http://127.0.0.1:9",
                    "--ledger.cache.enabled=false"))
            .flatMap(s -> s)
            .toArray(String[]::new);
    return builder.run(withDatabase);
  }
}
