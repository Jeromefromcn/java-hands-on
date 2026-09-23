package com.jerome.slledger.bootstrap.demo.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.support.ScopeNotActiveException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.RequestScope;
import org.springframework.web.context.request.ServletRequestAttributes;

class CounterpartyScopeTest {

  @Test
  @DisplayName(
      "a singleton holding an ObjectProvider looks the request-scoped bean up on every call,"
          + " so each request sees its own counterparty")
  void objectProviderResolvesPerRequest() {
    try (var context =
        contextWith(CounterpartyRequestContext.class, CurrentCounterpartyHolder.class)) {
      CurrentCounterpartyHolder holder = context.getBean(CurrentCounterpartyHolder.class);

      String first =
          inRequest(
              () -> {
                context.getBean(CounterpartyRequestContext.class).setCounterpartyId("CP-001");
                return holder.currentId();
              });
      String second = inRequest(holder::currentId);

      assertThat(first).isEqualTo("CP-001");
      assertThat(second).isNull();
    }
  }

  // A singleton is built once, at startup, when no request exists to take the bean from.
  // See docs/decisions/04-ioc-container.md.
  @Test
  @DisplayName(
      "injecting a request-scoped bean without a proxy straight into a singleton fails startup:"
          + " there is no request yet")
  void reproducesDirectInjectionOutsideRequest() {
    assertThatThrownBy(
            () -> contextWith(CounterpartyRequestContext.class, DirectlyInjectedHolder.class))
        .isInstanceOf(BeanCreationException.class)
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No thread-bound request found");
  }

  @Test
  @DisplayName(
      "with a scoped proxy the singleton is injected at startup with a CGLIB stand-in that"
          + " finds the current request's bean on each call, and fails only when called outside one")
  void scopedProxyResolvesPerCall() {
    try (var context =
        contextWith(ProxiedCounterpartyRequestContext.class, ProxyInjectedHolder.class)) {
      ProxyInjectedHolder holder = context.getBean(ProxyInjectedHolder.class);
      assertThat(AopUtils.isCglibProxy(holder.injected())).isTrue();

      String first =
          inRequest(
              () -> {
                holder.injected().setCounterpartyId("CP-001");
                return holder.currentId();
              });
      String second = inRequest(holder::currentId);

      assertThat(first).isEqualTo("CP-001");
      assertThat(second).isNull();
      assertThatThrownBy(holder::currentId)
          .isInstanceOf(ScopeNotActiveException.class)
          .rootCause()
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("No thread-bound request found");
    }
  }

  // No servlet container runs here, so the request scope is registered by hand.
  private static AnnotationConfigApplicationContext contextWith(Class<?>... classes) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.getBeanFactory().registerScope(WebApplicationContext.SCOPE_REQUEST, new RequestScope());
    context.register(classes);
    context.refresh();
    return context;
  }

  private static <T> T inRequest(Supplier<T> action) {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try {
      return action.get();
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }
}
