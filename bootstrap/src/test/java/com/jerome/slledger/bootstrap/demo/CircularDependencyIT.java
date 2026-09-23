package com.jerome.slledger.bootstrap.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.stereotype.Component;

class CircularDependencyIT {

  @Component
  static class Alpha {
    Alpha(Beta beta) {}
  }

  @Component
  static class Beta {
    Beta(Alpha alpha) {}
  }

  @Component
  static class AlphaSetter {
    BetaSetter beta;

    @Autowired
    void setBeta(BetaSetter beta) {
      this.beta = beta;
    }
  }

  @Component
  static class BetaSetter {
    AlphaSetter alpha;

    @Autowired
    void setAlpha(AlphaSetter alpha) {
      this.alpha = alpha;
    }
  }

  // The runner defaults to Spring Boot's policy (circular references off). Allowing them here is
  // what plain Spring Framework does, and what spring.main.allow-circular-references=true does.
  private final ApplicationContextRunner allowingCycles =
      new ApplicationContextRunner().withAllowCircularReferences(true);

  // The failure is Spring refusing, correctly: neither object can exist before the other.
  // See docs/decisions/04-ioc-container.md.
  @Test
  @DisplayName(
      "constructor injection in a cycle fails startup even with circular references allowed:"
          + " neither bean can be constructed first")
  void reproducesConstructorCircularDependency() {
    allowingCycles
        .withUserConfiguration(Alpha.class, Beta.class)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(BeanCurrentlyInCreationException.class);
            });
  }

  @Test
  @DisplayName(
      "setter injection in a cycle succeeds once circular references are allowed: an incomplete"
          + " instance is handed out early and filled in afterwards")
  void setterCycleResolvesWhenCyclesAllowed() {
    allowingCycles
        .withUserConfiguration(AlphaSetter.class, BetaSetter.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              AlphaSetter alpha = context.getBean(AlphaSetter.class);
              BetaSetter beta = context.getBean(BetaSetter.class);
              assertThat(alpha.beta).isSameAs(beta);
              assertThat(beta.alpha).isSameAs(alpha);
            });
  }

  @Test
  @DisplayName(
      "the same setter cycle fails under SpringApplication, which turns circular references off"
          + " by default since Spring Boot 2.6")
  void reproducesSetterCycleRejectedBySpringBootDefault() {
    SpringApplicationBuilder app =
        new SpringApplicationBuilder(AlphaSetter.class, BetaSetter.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .logStartupInfo(false);

    assertThatThrownBy(app::run).hasRootCauseInstanceOf(BeanCurrentlyInCreationException.class);
  }
}
