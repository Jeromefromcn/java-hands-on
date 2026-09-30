package com.jerome.slledger.adapter.persistence.loan;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.adapter.persistence.testing.LedgerFixtures;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercises 4 and 11: what the persistence context does on its own, and the cache that stays off.
 *
 * <p>The context starts with production's {@code use_second_level_cache: false}.
 */
class PersistenceContextIT {

  private static ConfigurableApplicationContext context;
  private static LoanPositionJpaRepository jpa;
  private static TransactionTemplate tx;
  private static JdbcClient jdbc;
  private static Statistics statistics;
  private static EntityManagerFactory emf;
  private static final StringBuilder EVIDENCE = new StringBuilder();

  @BeforeAll
  static void start() {
    context =
        LedgerDb.start(
            new String[] {"spring.jpa.properties.hibernate.cache.use_second_level_cache=false"},
            PositionReport.class,
            ReadOnlyPositionReport.class);
    jpa = context.getBean(LoanPositionJpaRepository.class);
    tx = context.getBean(TransactionTemplate.class);
    jdbc = context.getBean(JdbcClient.class);
    emf = context.getBean(EntityManagerFactory.class);
    statistics = emf.unwrap(SessionFactoryImplementor.class).getStatistics();
    new LedgerFixtures(jdbc)
        .counterparty("CP-001")
        .security("HK0000000001", "41.26")
        .position("P1", "CP-001", "HK0000000001", 100)
        .position("P2", "CP-001", "HK0000000001", 100);
  }

  @AfterAll
  static void stop() {
    Evidence.write("06-persistence-context.txt", EVIDENCE.toString());
    context.close();
  }

  @BeforeEach
  void resetStatistics() {
    statistics.clear();
  }

  @Test
  void twoFindsOfOneIdInOneTransactionIssueOneSelect() {
    var both =
        tx.execute(s -> new Object[] {jpa.findById("P1").orElseThrow(), jpa.findById("P1").get()});

    assertThat(both[1]).isSameAs(both[0]);
    assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    record("first-level cache", "two findById(P1), one transaction", statistics);
  }

  /**
   * A method meant only to read changes a managed entity for display and never saves it. Dirty
   * checking writes the change at commit. See docs/decisions/06-persistence.md.
   */
  @Test
  void reproducesAccidentalDirtyWrite() {
    String shown = context.getBean(PositionReport.class).describe("P1");

    assertThat(shown).contains("hkd");
    assertThat(collateralCurrency("P1")).isEqualTo("hkd");
    assertThat(statistics.getEntityUpdateCount()).isEqualTo(1);
    record("dirty write, @Transactional", "describe(P1)", statistics);
  }

  @Test
  void readOnlyLeavesTheRowAlone() {
    ReadOnlyPositionReport.Observed observed =
        context.getBean(ReadOnlyPositionReport.class).describe("P2");

    assertThat(observed.shown()).contains("hkd");
    assertThat(collateralCurrency("P2")).isEqualTo("HKD");
    assertThat(statistics.getEntityUpdateCount()).isZero();
    assertThat(statistics.getFlushCount()).isZero();
    assertThat(observed.flushMode()).isEqualTo(FlushMode.MANUAL);
    assertThat(observed.defaultReadOnly()).isTrue();
    assertThat(observed.entityReadOnly()).isTrue();
    record(
        "dirty write, @Transactional(readOnly = true)",
        "describe(P2); flushMode="
            + observed.flushMode()
            + ", session.isDefaultReadOnly="
            + observed.defaultReadOnly()
            + ", session.isReadOnly(entity)="
            + observed.entityReadOnly(),
        statistics);
  }

  @Test
  void theSecondLevelCacheIsOff() {
    tx.executeWithoutResult(s -> jpa.findById("P1").orElseThrow());
    tx.executeWithoutResult(s -> jpa.findById("P1").orElseThrow());

    assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    assertThat(statistics.getSecondLevelCacheHitCount()).isZero();
    assertThat(statistics.getSecondLevelCachePutCount()).isZero();
    boolean enabled =
        emf.unwrap(SessionFactoryImplementor.class)
            .getSessionFactoryOptions()
            .isSecondLevelCacheEnabled();
    assertThat(enabled).isFalse();
    record(
        "second-level cache",
        "findById(P1) in two transactions; isSecondLevelCacheEnabled=" + enabled,
        statistics);
  }

  /**
   * What production's explicit setting is worth today: nothing measurable. Without it Hibernate
   * still reports the cache disabled, because no region factory is on the classpath. The setting is
   * kept for the day one arrives (stage 9 may add a JCache provider), when the default would follow
   * the classpath and the explicit {@code false} would not. That future is not measured.
   */
  @Test
  void withoutTheSettingTheCacheIsStillOffForLackOfARegionFactory() {
    try (var defaults = LedgerDb.start()) {
      var options =
          defaults
              .getBean(EntityManagerFactory.class)
              .unwrap(SessionFactoryImplementor.class)
              .getSessionFactoryOptions();
      assertThat(options.isSecondLevelCacheEnabled()).isFalse();
      EVIDENCE.append(
          "%-46s | isSecondLevelCacheEnabled=%s, regionFactory=%s%n"
              .formatted(
                  "second-level cache, property not set",
                  options.isSecondLevelCacheEnabled(),
                  options
                      .getServiceRegistry()
                      .requireService(org.hibernate.cache.spi.RegionFactory.class)
                      .getClass()
                      .getSimpleName()));
    }
  }

  private static String collateralCurrency(String id) {
    return jdbc.sql("SELECT collateral_currency FROM loan_position WHERE id = ?")
        .param(id)
        .query(String.class)
        .single();
  }

  private static void record(String scenario, String action, Statistics s) {
    EVIDENCE.append(
        "%-46s | %s%n%-46s |   prepared=%d flushes=%d entityUpdates=%d l2Hits=%d l2Puts=%d%n"
            .formatted(
                scenario,
                action,
                "",
                s.getPrepareStatementCount(),
                s.getFlushCount(),
                s.getEntityUpdateCount(),
                s.getSecondLevelCacheHitCount(),
                s.getSecondLevelCachePutCount()));
  }

  /** Test-only. "Normalises" the currency for display on the managed entity itself. */
  static class PositionReport {

    private final LoanPositionJpaRepository jpa;

    PositionReport(LoanPositionJpaRepository jpa) {
      this.jpa = jpa;
    }

    @Transactional
    public String describe(String id) {
      LoanPositionEntity entity = jpa.findById(id).orElseThrow();
      entity.collateralCurrency = entity.collateralCurrency.toLowerCase();
      return entity.id + " " + entity.collateralAmount + " " + entity.collateralCurrency;
    }
  }

  /**
   * The same method, read-only, returning what Spring set on the session. Returned, not kept in
   * fields: the bean is a proxy, and a field written by the target is not the proxy's field.
   */
  static class ReadOnlyPositionReport {

    record Observed(
        String shown, FlushMode flushMode, boolean defaultReadOnly, boolean entityReadOnly) {}

    private final LoanPositionJpaRepository jpa;
    private final EntityManager entityManager;

    ReadOnlyPositionReport(LoanPositionJpaRepository jpa, EntityManager entityManager) {
      this.jpa = jpa;
      this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public Observed describe(String id) {
      Session session = entityManager.unwrap(Session.class);
      LoanPositionEntity entity = jpa.findById(id).orElseThrow();
      entity.collateralCurrency = entity.collateralCurrency.toLowerCase();
      return new Observed(
          entity.id + " " + entity.collateralAmount + " " + entity.collateralCurrency,
          session.getHibernateFlushMode(),
          session.isDefaultReadOnly(),
          session.isReadOnly(entity));
    }
  }
}
