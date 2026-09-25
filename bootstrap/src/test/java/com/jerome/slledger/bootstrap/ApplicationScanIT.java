package com.jerome.slledger.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.SharedPostgres;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * The real application component-scans {@code com.jerome.slledger.bootstrap}, and the test
 * classpath puts every stage's demos under that package too. None of them may reach the
 * application.
 */
class ApplicationScanIT {

  private static final String DEMOS = "com.jerome.slledger.bootstrap.demo";

  @Test
  @DisplayName("the application's component scan picks up none of the test-only demo beans")
  void noDemoBeansInTheApplication() {
    // Since stage 6 the application needs a database; it gets one of its own. Passed as arguments,
    // the highest-precedence source, so no profile's YAML can override it.
    String[] datasource =
        Arrays.stream(SharedPostgres.freshDatabase()).map(p -> "--" + p).toArray(String[]::new);
    try (var context =
        new SpringApplicationBuilder(SlLedgerApplication.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run(datasource)) {
      List<String> leaked =
          Arrays.stream(context.getBeanDefinitionNames())
              .filter(
                  name -> {
                    Class<?> type = context.getType(name);
                    return type != null && type.getName().startsWith(DEMOS);
                  })
              .toList();

      assertThat(leaked).isEmpty();
    }
  }
}
