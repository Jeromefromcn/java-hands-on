package com.jerome.slledger.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The synthetic seed data reaches a database only in the local profile: no other profile's YAML,
 * nor the base file, lists {@code db/seed-local} among Flyway's locations.
 */
class SeedLocationTest {

  @ParameterizedTest(name = "{0} loads the seed: {1}")
  @CsvSource({
    "application.yml, false",
    "application-test.yml, false",
    "application-prod.yml, false",
    "application-local.yml, true"
  })
  void onlyLocalLoadsTheSeed(String file, boolean seeded) throws IOException {
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader().load(file, new ClassPathResource(file));

    String locations = String.valueOf(sources.getFirst().getProperty("spring.flyway.locations"));

    assertThat(locations.contains("seed-local")).isEqualTo(seeded);
  }
}
