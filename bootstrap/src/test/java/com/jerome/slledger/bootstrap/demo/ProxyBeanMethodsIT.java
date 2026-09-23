package com.jerome.slledger.bootstrap.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ProxyBeanMethodsIT {

  record Widget() {}

  record Holder(Widget widget) {}

  @Configuration
  static class TrueConfig {
    @Bean
    Widget widget() {
      return new Widget();
    }

    @Bean
    Holder holder() {
      return new Holder(widget());
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class FalseConfig {
    @Bean
    Widget widget() {
      return new Widget();
    }

    @Bean
    Holder holder() {
      return new Holder(widget());
    }
  }

  private final ApplicationContextRunner runner = new ApplicationContextRunner();

  @Test
  @DisplayName(
      "with proxyBeanMethods = true, calling widget() from holder() returns the container's"
          + " singleton, because the call goes through a CGLIB subclass of the config")
  void proxiedConfigReturnsTheSingleton() {
    runner
        .withUserConfiguration(TrueConfig.class)
        .run(
            context -> {
              assertThat(context.getBean(Holder.class).widget())
                  .isSameAs(context.getBean(Widget.class));
              assertThat(context.getBean(TrueConfig.class).getClass().getName())
                  .contains("$$SpringCGLIB$$");
            });
  }

  @Test
  @DisplayName(
      "with proxyBeanMethods = false, widget() is a plain Java call, so holder() gets a second"
          + " Widget the container never sees")
  void unproxiedConfigCreatesASecondInstance() {
    runner
        .withUserConfiguration(FalseConfig.class)
        .run(
            context -> {
              assertThat(context.getBean(Holder.class).widget())
                  .isNotSameAs(context.getBean(Widget.class));
              assertThat(context.getBean(FalseConfig.class).getClass())
                  .isEqualTo(FalseConfig.class);
            });
  }
}
