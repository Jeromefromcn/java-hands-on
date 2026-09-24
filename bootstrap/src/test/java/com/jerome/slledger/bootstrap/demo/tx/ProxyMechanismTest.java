package com.jerome.slledger.bootstrap.demo.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.bootstrap.demo.tx.proxy.ConcretePositionQueries;
import com.jerome.slledger.bootstrap.demo.tx.proxy.CountingAspect;
import com.jerome.slledger.bootstrap.demo.tx.proxy.InterfacePositionQueries;
import com.jerome.slledger.bootstrap.demo.tx.proxy.PositionQueries;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Which proxy Spring builds, JDK or CGLIB, under Boot's default and with {@code
 * spring.aop.proxy-target-class=false}; and what a {@code final} method does to CGLIB advice.
 */
class ProxyMechanismTest {

  private static final Map<String, String> PROXY_CLASSES = new LinkedHashMap<>();

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class))
          .withBean(CountingAspect.class)
          .withBean(InterfacePositionQueries.class)
          .withBean(ConcretePositionQueries.class);

  @Test
  @DisplayName("Boot's default proxies by subclass: even the interface-backed bean is CGLIB")
  void bootDefaultIsCglib() {
    runner.run(
        context -> {
          PositionQueries interfaceBacked = context.getBean(PositionQueries.class);
          ConcretePositionQueries concrete = context.getBean(ConcretePositionQueries.class);

          assertThat(AopUtils.isCglibProxy(interfaceBacked)).isTrue();
          assertThat(AopUtils.isCglibProxy(concrete)).isTrue();
          // A subclass proxy is still an InterfacePositionQueries, so lookup by class works.
          assertThat(context.getBean(InterfacePositionQueries.class)).isSameAs(interfaceBacked);

          record("default, interface-backed", interfaceBacked);
          record("default, concrete", concrete);
        });
  }

  @Test
  @DisplayName(
      "proxy-target-class=false gives the interface-backed bean a JDK proxy, visible only by its"
          + " interface; the concrete bean has no interface and stays CGLIB")
  void proxyTargetClassFalseGivesJdkProxyWhereAnInterfaceExists() {
    runner
        .withPropertyValues("spring.aop.proxy-target-class=false")
        .run(
            context -> {
              PositionQueries interfaceBacked = context.getBean(PositionQueries.class);
              ConcretePositionQueries concrete = context.getBean(ConcretePositionQueries.class);

              assertThat(AopUtils.isJdkDynamicProxy(interfaceBacked)).isTrue();
              assertThat(AopUtils.isCglibProxy(concrete)).isTrue();
              // A JDK proxy implements the interface only; it is not an InterfacePositionQueries.
              assertThatThrownBy(() -> context.getBean(InterfacePositionQueries.class))
                  .isInstanceOf(NoSuchBeanDefinitionException.class);

              record("proxy-target-class=false, interface-backed", interfaceBacked);
              record("proxy-target-class=false, concrete", concrete);
            });
  }

  // The final method is not overridden by the CGLIB subclass, so the call runs on the proxy
  // instance itself and never reaches the advice. Nothing fails; the advice simply does not run.
  // Same root cause as self-invocation: advice exists only where the proxy intercepts a call.
  // See docs/decisions/05-aop-transactions.md
  @Test
  @DisplayName("a final method on a CGLIB proxy bypasses the advice silently")
  void reproducesFinalMethodBypassesAdvice() {
    runner.run(
        context -> {
          ConcretePositionQueries concrete = context.getBean(ConcretePositionQueries.class);
          CountingAspect aspect = context.getBean(CountingAspect.class);

          concrete.describe();
          assertThat(aspect.count()).isEqualTo(1);

          concrete.describeFinal();
          assertThat(aspect.count()).isEqualTo(1);
        });
  }

  private static void record(String configuration, Object bean) {
    String name = bean.getClass().getName();
    System.out.println(configuration + " -> " + name);
    PROXY_CLASSES.put(configuration, name);
  }

  @AfterAll
  static void writeEvidence() {
    StringBuilder out = new StringBuilder("Proxy class per configuration (ProxyMechanismTest)\n\n");
    PROXY_CLASSES.forEach(
        (configuration, name) ->
            out.append(configuration).append("\n  ").append(name).append("\n"));
    Evidence.write("05-proxy-classes.txt", out.toString());
  }
}
