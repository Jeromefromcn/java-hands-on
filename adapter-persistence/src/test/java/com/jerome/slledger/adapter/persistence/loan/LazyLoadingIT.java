package com.jerome.slledger.adapter.persistence.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jerome.slledger.adapter.persistence.accrual.AccrualEntryEntity;
import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.adapter.persistence.testing.LedgerFixtures;
import com.jerome.slledger.adapter.persistence.testing.SqlRecorder;
import com.jerome.slledger.application.port.PositionListQuery;
import com.jerome.slledger.application.port.PositionPage;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import java.util.Optional;
import org.hibernate.LazyInitializationException;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.ServletWebRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercise 3: what a controller serialising an entity would do, without a controller.
 *
 * <p>The context runs with production's {@code open-in-view: false}. The OSIV case installs the
 * interceptor Boot would register, by hand, around one simulated request.
 */
class LazyLoadingIT {

  private static final StringBuilder EVIDENCE = new StringBuilder();

  /**
   * Serialises fields, as a naive entity-returning controller would. The entry's back-reference to
   * its position is left out: serialising it would recurse, which is a different failure.
   */
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .changeDefaultVisibility(
              checker ->
                  checker
                      .withFieldVisibility(Visibility.ANY)
                      .withGetterVisibility(Visibility.NONE)
                      .withIsGetterVisibility(Visibility.NONE))
          .addMixIn(AccrualEntryEntity.class, IgnorePosition.class)
          .build();

  @JsonIgnoreProperties("position")
  private abstract static class IgnorePosition {}

  private static ConfigurableApplicationContext context;
  private static PositionLookup lookup;
  private static EntityManagerFactory emf;
  private static Statistics statistics;

  @BeforeAll
  static void start() {
    context = LedgerDb.start(new String[] {SqlRecorder.PROPERTY}, PositionLookup.class);
    lookup = context.getBean(PositionLookup.class);
    emf = context.getBean(EntityManagerFactory.class);
    statistics = emf.unwrap(SessionFactoryImplementor.class).getStatistics();
    new LedgerFixtures(context.getBean(JdbcClient.class))
        .counterparty("CP-001")
        .security("HK0000000001", "41.26")
        .position("P1", "CP-001", "HK0000000001", 100)
        .entries("P1", LocalDate.of(2026, 9, 1), 3, "0.41");
  }

  @AfterAll
  static void stop() {
    Evidence.write("06-lazy-loading.txt", EVIDENCE.toString());
    context.close();
  }

  @BeforeEach
  void reset() {
    statistics.clear();
    SqlRecorder.clear();
  }

  /**
   * The service returned; its transaction and persistence context are closed; the serialiser
   * touches {@code entries}. See docs/decisions/06-persistence.md
   */
  @Test
  void reproducesLazyInitializationOnSerialization() {
    LoanPositionEntity position = lookup.load("P1");

    assertThatThrownBy(() -> JSON.writeValueAsString(position))
        .hasRootCauseInstanceOf(LazyInitializationException.class);
    EVIDENCE.append(
        "open-in-view off: serialise after load returned%n  %s%n%n"
            .formatted(rootCauseOf(() -> JSON.writeValueAsString(position))));
  }

  /**
   * The same call inside the interceptor Boot registers when {@code open-in-view} is left on. The
   * serialisation succeeds, and it does so by querying after the transaction has ended.
   */
  @Test
  void osivHidesItAndQueriesOutsideTheTransaction() {
    OpenEntityManagerInViewInterceptor osiv = new OpenEntityManagerInViewInterceptor();
    osiv.setEntityManagerFactory(emf);
    ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest());

    osiv.preHandle(request);
    String json;
    int statementsByLoad;
    try {
      LoanPositionEntity position = lookup.load("P1");
      statementsByLoad = SqlRecorder.statements().size();
      json = JSON.writeValueAsString(position);
    } finally {
      osiv.afterCompletion(request, null);
    }

    var afterLoad =
        SqlRecorder.statements().subList(statementsByLoad, SqlRecorder.statements().size());
    var afterLoadInTx =
        SqlRecorder.inTransaction().subList(statementsByLoad, SqlRecorder.inTransaction().size());
    assertThat(json).contains("2026-09-03");
    assertThat(afterLoad).isNotEmpty();
    assertThat(afterLoadInTx).containsOnly(false);
    EVIDENCE.append(
        "open-in-view on (OpenEntityManagerInViewInterceptor around the call)%n".formatted());
    EVIDENCE.append("  statements inside load's transaction: %d%n".formatted(statementsByLoad));
    EVIDENCE.append(
        "  statements after load returned, during serialisation: %d%n".formatted(afterLoad.size()));
    for (int i = 0; i < afterLoad.size(); i++) {
      EVIDENCE.append(
          "    [transaction active: %s] %s%n".formatted(afterLoadInTx.get(i), afterLoad.get(i)));
    }
    EVIDENCE.append("  json: %s%n%n".formatted(json));
  }

  @Test
  void dtoProjectionSerialisesWithoutLazyAccess() {
    PositionPage page =
        context
            .getBean(org.springframework.transaction.support.TransactionTemplate.class)
            .execute(s -> context.getBean(PositionListQuery.class).page(Optional.empty(), 0, 10));
    statistics.clear();
    SqlRecorder.clear();

    String json = JSON.writeValueAsString(page);

    assertThat(json).contains("P1");
    assertThat(statistics.getPrepareStatementCount()).isZero();
    EVIDENCE.append(
        ("DTO projection (PositionListQuery row), serialised after its transaction%n"
                + "  statements during serialisation: %d%n  json: %s%n")
            .formatted(statistics.getPrepareStatementCount(), json));
  }

  private static String rootCauseOf(Runnable action) {
    try {
      action.run();
      return "no failure";
    } catch (RuntimeException e) {
      Throwable root = e;
      while (root.getCause() != null) {
        root = root.getCause();
      }
      return e.getClass().getName()
          + " <- … <- "
          + root.getClass().getName()
          + ": "
          + root.getMessage();
    }
  }

  /** Test-only. A service that returns the entity it loaded, as a controller would receive it. */
  static class PositionLookup {

    private final LoanPositionJpaRepository jpa;

    PositionLookup(LoanPositionJpaRepository jpa) {
      this.jpa = jpa;
    }

    @Transactional(readOnly = true)
    public LoanPositionEntity load(String id) {
      return jpa.findById(id).orElseThrow();
    }
  }
}
