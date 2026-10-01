package com.jerome.slledger.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.position.LedgerApp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code @PreAuthorize} and {@code @Transactional} on one method: which runs first?
 *
 * <p>By the numbers. Method security's pre-authorize advisor is ordered 200 ({@code
 * AuthorizationInterceptorsOrder.PRE_AUTHORIZE}); the transaction advisor is {@code
 * LOWEST_PRECEDENCE} unless {@code @EnableTransactionManagement(order = ...)} says otherwise. A
 * lower order is outer, so the authorization decision is made first and a refused call never begins
 * a transaction. The same numbers say what {@code @PostAuthorize} (500) does: it is outside the
 * transaction too, so it judges a result after the transaction has committed. The test records what
 * actually happens, then builds the opposite order by hand to show what a refusal costs when the
 * transaction is outer.
 */
class MethodSecurityOrderIT {

  private static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());
  private static final StringBuilder EVIDENCE =
      new StringBuilder("Stage 13 · section 5 — authorization before the transaction\n\n");

  private static ConfigurableApplicationContext app;
  private static OrderProbe probe;

  /** Test-only source, registered by class: not a component of the application. */
  public static class Probes {
    @Bean
    OrderProbe orderProbe() {
      return new OrderProbe();
    }

    @Bean
    ProbeAuthorization probeAuthorization() {
      return new ProbeAuthorization();
    }
  }

  public static class ProbeAuthorization {
    /** Called from the SpEL of the probe's {@code @PreAuthorize}: records where it runs. */
    public boolean recordAndAllow() {
      EVENTS.add("authorization decision   transaction active: " + active());
      return true;
    }
  }

  public static class OrderProbe {
    @PreAuthorize("@probeAuthorization.recordAndAllow() and hasRole('ledger-trader')")
    @Transactional
    public String work() {
      EVENTS.add("method body              transaction active: " + active());
      return "done";
    }
  }

  /** The opposite order, assembled by hand below; no bean reference, so no application context. */
  public static class ReversedProbe {
    @PreAuthorize("hasRole('ledger-trader')")
    @Transactional
    public String work() {
      EVENTS.add("method body              transaction active: " + active());
      return "done";
    }
  }

  private static boolean active() {
    return TransactionSynchronizationManager.isActualTransactionActive();
  }

  @BeforeAll
  static void start() {
    app = LedgerApp.startWeb(List.of(Probes.class));
    probe = app.getBean(OrderProbe.class);
    app.getBean(JpaTransactionManager.class)
        .addListener(
            new TransactionExecutionListener() {
              @Override
              public void afterBegin(TransactionExecution tx, Throwable error) {
                EVENTS.add("transaction begun        (a connection is taken)");
              }

              @Override
              public void afterCommit(TransactionExecution tx, Throwable error) {
                EVENTS.add("transaction committed");
              }

              @Override
              public void afterRollback(TransactionExecution tx, Throwable error) {
                EVENTS.add("transaction rolled back");
              }
            });
  }

  @AfterAll
  static void stop() {
    Evidence.write("13-security-vs-transaction.txt", EVIDENCE.toString());
    app.close();
  }

  @BeforeEach
  @AfterEach
  void reset() {
    EVENTS.clear();
    SecurityContextHolder.clearContext();
  }

  private static void callerWith(String... roles) {
    String[] authorities =
        java.util.Arrays.stream(roles).map(r -> "ROLE_" + r).toArray(String[]::new);
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("caller", "n/a", authorities));
  }

  private static void record(String title, Throwable thrown) {
    EVIDENCE.append(title).append('\n');
    EVENTS.forEach(event -> EVIDENCE.append("  ").append(event).append('\n'));
    EVIDENCE
        .append("  result: ")
        .append(thrown == null ? "returned" : thrown.getClass().getSimpleName())
        .append("\n\n");
  }

  @Test
  @DisplayName("the advisors' orders: authorization is outer")
  void orders() {
    Map<String, Advisor> advisors = app.getBeansOfType(Advisor.class);
    int authorization = -1;
    int transaction = -1;
    StringBuilder listing = new StringBuilder("Advisors in the context, with their order:\n");
    for (Map.Entry<String, Advisor> advisor : advisors.entrySet()) {
      int order =
          advisor.getValue() instanceof Ordered ordered
              ? ordered.getOrder()
              : Ordered.LOWEST_PRECEDENCE;
      listing.append("  %-12d %s%n".formatted(order, advisor.getKey()));
      if (advisor.getKey().toLowerCase().contains("preauthorizeauthorizationadvisor")) {
        authorization = order;
      }
      if (advisor.getKey().toLowerCase().contains("internaltransactionadvisor")) {
        transaction = order;
      }
    }
    EVIDENCE.append(listing).append('\n');

    assertThat(authorization).as("pre-authorize advisor found").isNotEqualTo(-1);
    assertThat(transaction).as("transaction advisor found").isNotEqualTo(-1);
    assertThat(authorization).as("lower order is outer").isLessThan(transaction);
  }

  @Test
  @DisplayName("a permitted call: the decision is made with no transaction, the body runs in one")
  void permitted() {
    callerWith("ledger-trader");

    assertThat(probe.work()).isEqualTo("done");

    record("A permitted call (ROLE_ledger-trader):", null);
    assertThat(EVENTS)
        .containsExactly(
            "authorization decision   transaction active: false",
            "transaction begun        (a connection is taken)",
            "method body              transaction active: true",
            "transaction committed");
  }

  @Test
  @DisplayName("a denied call never begins a transaction")
  void denied() {
    callerWith("ledger-counterparty");
    Throwable thrown = null;
    try {
      probe.work();
    } catch (AuthorizationDeniedException e) {
      thrown = e;
    }

    record("A denied call (ROLE_ledger-counterparty):", thrown);
    assertThat(thrown).isNotNull();
    assertThat(EVENTS).containsExactly("authorization decision   transaction active: false");
  }

  @Test
  @DisplayName("reproducesTransactionOpenedBeforeAuthorization")
  void reproducesTransactionOpenedBeforeAuthorization() {
    // See docs/decisions/13-security.md
    // The same two annotations with the transaction interceptor placed outside the authorization
    // one, as it would be if the transaction advisor were given a lower order than method security.
    ProxyFactory factory = new ProxyFactory(new ReversedProbe());
    factory.setProxyTargetClass(true);
    TransactionInterceptor transactions = new TransactionInterceptor();
    transactions.setTransactionManager(app.getBean(JpaTransactionManager.class));
    transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    factory.addAdvice(transactions);
    factory.addAdvice(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
    ReversedProbe reversed = (ReversedProbe) factory.getProxy();
    callerWith("ledger-counterparty");

    assertThatThrownBy(reversed::work).isInstanceOf(AuthorizationDeniedException.class);

    record(
        "The opposite order, built by hand (transaction outside authorization), denied call:",
        null);
    // A refused call took a connection, began a transaction, and rolled it back.
    assertThat(EVENTS)
        .containsExactly(
            "transaction begun        (a connection is taken)", "transaction rolled back");
  }
}
