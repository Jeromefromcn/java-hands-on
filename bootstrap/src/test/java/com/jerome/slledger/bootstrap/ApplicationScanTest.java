package com.jerome.slledger.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * The real application component-scans {@code com.jerome.slledger.bootstrap}, and the test
 * classpath puts stage 5's demos under that package too. None of them may reach the application.
 */
class ApplicationScanTest {

  private static final String DEMOS = "com.jerome.slledger.bootstrap.demo.tx";

  @Test
  @DisplayName("the application's component scan picks up none of stage 5's test-only demo beans")
  void noTransactionDemoBeansInTheApplication() {
    try (var context =
        new SpringApplicationBuilder(SlLedgerApplication.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            // See ConfigurationPrecedenceTest: the JDBC starter is on the test classpath only.
            .properties(
                "spring.autoconfigure.exclude="
                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
            .run()) {
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
