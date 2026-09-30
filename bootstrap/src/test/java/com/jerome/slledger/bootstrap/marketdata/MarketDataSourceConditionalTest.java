package com.jerome.slledger.bootstrap.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.bootstrap.demo.conditional.DiagnosticsBean;
import com.jerome.slledger.bootstrap.timing.TimingBeanPostProcessor;
import com.jerome.slledger.domain.accrual.ReferenceRates;
import com.jerome.slledger.domain.money.Rate;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

class MarketDataSourceConditionalTest {

  @Configuration
  @ComponentScan(basePackageClasses = MockReferenceRates.class)
  static class ScanMarketData {}

  private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 3, 2);

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(TimingBeanPostProcessor.class)
          .withUserConfiguration(ScanMarketData.class);

  @Test
  @DisplayName("with no marketdata.source set, the mock source is wired and nothing else")
  void mockIsTheDefault() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(ReferenceRates.class);
          assertThat(context).hasBean("mockReferenceRates").doesNotHaveBean("realReferenceRates");
          assertThat(context.getBean(ReferenceRates.class).on(BUSINESS_DATE))
              .isEqualTo(Rate.of("0.0365"));
        });
  }

  @Test
  @DisplayName("marketdata.source=mock selects the mock source explicitly")
  void mockWhenAskedFor() {
    runner
        .withPropertyValues("marketdata.source=mock")
        .run(
            context -> {
              assertThat(context).hasSingleBean(ReferenceRates.class);
              assertThat(context)
                  .hasBean("mockReferenceRates")
                  .doesNotHaveBean("realReferenceRates");
            });
  }

  @Test
  @DisplayName(
      "marketdata.source=real selects the real source, a stub that refuses every call until"
          + " stage 7")
  void realWhenAskedFor() {
    runner
        .withPropertyValues("marketdata.source=real")
        .run(
            context -> {
              assertThat(context).hasSingleBean(ReferenceRates.class);
              assertThat(context)
                  .hasBean("realReferenceRates")
                  .doesNotHaveBean("mockReferenceRates");
              ReferenceRates rates = context.getBean(ReferenceRates.class);
              assertThatThrownBy(() -> rates.on(BUSINESS_DATE))
                  .isInstanceOf(UnsupportedOperationException.class)
                  .hasMessage(
                      "a real reference rate source is not scheduled; stage 7 built a price source"
                          + " instead");
            });
  }

  @Test
  @DisplayName(
      "the selected source is @Timed, so what the context hands out is the timing proxy, not the"
          + " class")
  void selectedSourceIsTimed() {
    runner.run(
        context ->
            assertThat(Proxy.isProxyClass(context.getBean(ReferenceRates.class).getClass()))
                .isTrue());
  }

  @Test
  @DisplayName(
      "the hand-written OS condition registers DiagnosticsBean exactly when this JVM runs on"
          + " Linux")
  void diagnosticsBeanFollowsTheRunningOs() {
    boolean onLinux = System.getProperty("os.name").startsWith("Linux");
    new ApplicationContextRunner()
        .withUserConfiguration(DiagnosticsBean.class)
        .run(context -> assertThat(context.containsBean("diagnosticsBean")).isEqualTo(onLinux));
  }

  @Test
  @DisplayName(
      "the OS condition reads os.name from the Environment, so a different OS turns it off")
  void diagnosticsBeanAbsentOnAnotherOs() {
    new ApplicationContextRunner()
        .withUserConfiguration(DiagnosticsBean.class)
        .withSystemProperties("os.name=Windows 11")
        .run(context -> assertThat(context.containsBean("diagnosticsBean")).isFalse());
  }
}
