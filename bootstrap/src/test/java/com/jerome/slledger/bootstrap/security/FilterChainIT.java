package com.jerome.slledger.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.web.trace.TraceIdFilter;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.DelegatingFilterProxy;

/**
 * The full list of filters a request passes, in order: the container's filters (Boot's observation
 * filter, {@code TraceIdFilter}, the security chain) and, inside the security chain, Spring
 * Security's own. Where {@code TraceIdFilter} sits relative to security decides whether a 401
 * carries a trace id.
 */
class FilterChainIT {

  private static ConfigurableApplicationContext app;

  @BeforeAll
  static void start() {
    app = LedgerApp.startWeb();
  }

  /** (order, name) of every filter the servlet container is given, outermost first. */
  private static List<Map.Entry<Integer, String>> containerFilters() {
    Map<Filter, Map.Entry<Integer, String>> byFilter = new IdentityHashMap<>();
    app.getBeansOfType(AbstractFilterRegistrationBean.class)
        .forEach(
            (name, registration) ->
                byFilter.put(
                    registration.getFilter(),
                    Map.entry(registration.getOrder(), describe(name, registration.getFilter()))));
    app.getBeansOfType(Filter.class)
        .forEach(
            (name, filter) -> {
              // The chain proxy is registered through the DelegatingFilterProxy above.
              if (!(filter instanceof FilterChainProxy)) {
                byFilter.computeIfAbsent(filter, f -> Map.entry(orderOf(f), describe(name, f)));
              }
            });
    List<Map.Entry<Integer, String>> filters = new ArrayList<>(byFilter.values());
    filters.sort(Map.Entry.comparingByKey());
    return filters;
  }

  private static int orderOf(Filter filter) {
    if (filter instanceof Ordered ordered) {
      return ordered.getOrder();
    }
    Integer annotated = OrderUtils.getOrder(filter.getClass());
    return annotated == null ? Ordered.LOWEST_PRECEDENCE : annotated;
  }

  private static String describe(String beanName, Filter filter) {
    return filter instanceof DelegatingFilterProxy
        ? "springSecurityFilterChain (DelegatingFilterProxy -> FilterChainProxy)"
        : filter.getClass().getSimpleName() + " (" + beanName + ")";
  }

  @Test
  @DisplayName("TraceIdFilter runs before the security chain, so a 401 carries a trace id")
  void traceIdFilterIsOutsideSecurity() {
    List<String> names = containerFilters().stream().map(Map.Entry::getValue).toList();

    int trace = indexOf(names, TraceIdFilter.class.getSimpleName());
    int security = indexOf(names, "springSecurityFilterChain");

    assertThat(trace).isGreaterThanOrEqualTo(0);
    assertThat(security).isGreaterThan(trace);
  }

  @Test
  @DisplayName(
      "the security chain is stateless token auth: no session, CSRF, form, basic or logout")
  void chainHasNoStatefulFilters() {
    FilterChainProxy proxy = app.getBean(FilterChainProxy.class);
    List<SecurityFilterChain> chains = proxy.getFilterChains();
    List<String> filters =
        chains.getFirst().getFilters().stream().map(f -> f.getClass().getSimpleName()).toList();

    assertThat(chains).hasSize(1);
    assertThat(filters)
        .contains(
            "SecurityContextHolderFilter",
            "BearerTokenAuthenticationFilter",
            "ExceptionTranslationFilter",
            "AuthorizationFilter")
        .doesNotContain(
            "CsrfFilter",
            "UsernamePasswordAuthenticationFilter",
            "BasicAuthenticationFilter",
            "LogoutFilter",
            "DefaultLoginPageGeneratingFilter");
    // Authentication happens before the exception translation that turns a refusal into a 401, and
    // the authorization decision comes last of the three.
    assertThat(filters.indexOf("BearerTokenAuthenticationFilter"))
        .isLessThan(filters.indexOf("ExceptionTranslationFilter"));
    assertThat(filters.indexOf("ExceptionTranslationFilter"))
        .isLessThan(filters.indexOf("AuthorizationFilter"));
  }

  @Test
  @DisplayName("stateless: an authenticated request creates no session and sets no cookie")
  void noSession() {
    var result =
        LedgerApp.http(app, TestTokens.trader())
            .get()
            .uri("/loans")
            .exchange()
            .returnResult(String.class);

    assertThat(result.getResponseHeaders().get("Set-Cookie")).isNull();
  }

  @AfterAll
  static void evidenceAndStop() {
    StringBuilder out =
        new StringBuilder(
            "Stage 13 · section 2 — every filter a request passes (FilterChainIT)\n"
                + "Real Tomcat. Container filters sorted by order, outermost first; the security\n"
                + "chain's own filters, in order, below.\n\n"
                + "Container filters (order  name):\n");
    for (Map.Entry<Integer, String> filter : containerFilters()) {
      out.append("  %11d  %s%n".formatted(filter.getKey(), filter.getValue()));
    }
    out.append("\nSpring Security filters, in order:\n");
    int n = 1;
    for (SecurityFilterChain chain : app.getBean(FilterChainProxy.class).getFilterChains()) {
      for (Filter filter : chain.getFilters()) {
        out.append("  %2d  %s%n".formatted(n++, filter.getClass().getSimpleName()));
      }
    }
    Evidence.write("13-filter-chain.txt", out.toString());
    app.close();
  }

  private static int indexOf(List<String> names, String prefix) {
    for (int i = 0; i < names.size(); i++) {
      if (names.get(i).startsWith(prefix)) {
        return i;
      }
    }
    return -1;
  }
}
