package com.jerome.slledger.adapter.persistence.loan;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jerome.slledger.adapter.persistence.accrual.AccrualEntryEntity;
import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.adapter.persistence.testing.LedgerFixtures;
import com.jerome.slledger.adapter.persistence.testing.SqlRecorder;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercise 2: 100 positions with 5 accrual entries each, loaded with their entries four ways, and
 * {@code join fetch} combined with a page.
 *
 * <p>Statements are counted with Hibernate {@code Statistics}; SQL text comes from {@link
 * SqlRecorder}. Each variant runs in its own transaction, so no first-level cache carries over.
 */
class NPlusOneIT {

  private static final int POSITIONS = 100;
  private static final int ENTRIES_EACH = 5;
  private static final StringBuilder EVIDENCE = new StringBuilder();

  /** Run only with a page, on {@link #paging}. */
  private static final String PAGED_ONLY =
      "select distinct p from LoanPositionEntity p join fetch p.entries order by p.id";

  /** Run both with and without a page, each time on a fresh session factory. */
  private static final String SHARED =
      "select p from LoanPositionEntity p join fetch p.entries order by p.id";

  private static Db defaults;
  private static Db batched;
  private static Db strict;
  private static Db paging;

  @BeforeAll
  static void start() {
    defaults = new Db();
    batched = new Db("spring.jpa.properties.hibernate.default_batch_fetch_size=25");
    strict =
        new Db(
            "spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch=true");
    // Its own session factory: Hibernate's query plan cache is per factory, and the tests below
    // show that what ran before on a factory changes what a page returns.
    paging = new Db();
  }

  @AfterAll
  static void stop() {
    Evidence.write("06-n-plus-one.txt", EVIDENCE.toString());
    defaults.context.close();
    batched.context.close();
    strict.context.close();
    paging.context.close();
  }

  /** One query for the positions, then one per position the moment its entries are touched. */
  @Test
  void reproducesNPlusOne() {
    // See docs/decisions/06-persistence.md
    Loaded loaded =
        defaults.measure(
            em ->
                em.createQuery("from LoanPositionEntity p order by p.id", LoanPositionEntity.class)
                    .getResultList());

    assertThat(loaded.statements).isEqualTo(POSITIONS + 1);
    loaded.assertComplete(POSITIONS);
    record("lazy, entries touched per position", loaded);
  }

  @Test
  void joinFetchLoadsInOneStatement() {
    Loaded loaded =
        defaults.measure(
            em ->
                em.createQuery(
                        "select distinct p from LoanPositionEntity p join fetch p.entries"
                            + " order by p.id",
                        LoanPositionEntity.class)
                    .getResultList());

    assertThat(loaded.statements).isEqualTo(1);
    loaded.assertComplete(POSITIONS);
    record("join fetch", loaded);
  }

  /** The JPA entity graph Spring Data's {@code @EntityGraph} builds, applied directly. */
  @Test
  void entityGraphLoadsInOneStatement() {
    Loaded loaded =
        defaults.measure(
            em -> {
              EntityGraph<LoanPositionEntity> graph =
                  em.createEntityGraph(LoanPositionEntity.class);
              graph.addAttributeNodes("entries");
              return em.createQuery(
                      "from LoanPositionEntity p order by p.id", LoanPositionEntity.class)
                  .setHint("jakarta.persistence.fetchgraph", graph)
                  .getResultList();
            });

    assertThat(loaded.statements).isEqualTo(1);
    loaded.assertComplete(POSITIONS);
    record("entity graph (fetchgraph: entries)", loaded);
  }

  /**
   * Batch fetching: touching one position's entries loads them for up to 25 uninitialised positions
   * at once. 100 / 25 = 4 batches, plus the positions query.
   */
  @Test
  void batchSizeLoadsInFewStatements() {
    Loaded loaded =
        batched.measure(
            em ->
                em.createQuery("from LoanPositionEntity p order by p.id", LoanPositionEntity.class)
                    .getResultList());

    assertThat(loaded.statements).isEqualTo(1 + POSITIONS / 25);
    loaded.assertComplete(POSITIONS);
    record("default_batch_fetch_size=25", loaded);
  }

  /**
   * The trap the roadmap names, as Hibernate 7.4 no longer springs it: a page over a plain {@code
   * join fetch} is rewritten so the page is taken over positions in a subquery, then joined to
   * entries. One statement, 10 positions, 60 entities, no warning.
   */
  @Test
  void hibernate7PagesTheRootInASubquery() {
    Warnings warnings = new Warnings();
    Loaded loaded;
    try {
      loaded =
          paging.measure(
              em ->
                  em.createQuery(PAGED_ONLY, LoanPositionEntity.class)
                      .setMaxResults(10)
                      .getResultList());
    } finally {
      warnings.close();
    }

    assertThat(loaded.statements).isEqualTo(1);
    assertThat(loaded.positions).isEqualTo(10);
    assertThat(loaded.entries).isEqualTo(10 * ENTRIES_EACH);
    assertThat(loaded.entitiesLoaded).isEqualTo(10 + 10 * ENTRIES_EACH);
    assertThat(loaded.sql.getFirst()).contains("from (select distinct").contains("fetch first");
    assertThat(warnings.hhh90003004()).isEmpty();
    record("join fetch + setMaxResults(10)", loaded);
  }

  /**
   * Still reachable: once the order refers to the fetched collection, the page can no longer be
   * taken over positions alone. Hibernate reads every position × entry row, hydrates all of them,
   * and pages in memory, with only a warning. See docs/decisions/06-persistence.md
   */
  @Test
  void reproducesInMemoryPaginationWithJoinFetch() {
    Warnings warnings = new Warnings();
    Loaded loaded;
    try {
      loaded = defaults.measure(NPlusOneIT::pageOrderedByAnEntryColumn);
    } finally {
      warnings.close();
    }

    assertThat(loaded.positions).isEqualTo(10);
    assertThat(loaded.entries).isEqualTo(10 * ENTRIES_EACH);
    assertThat(loaded.statements).isEqualTo(1);
    assertThat(loaded.sql.getFirst()).doesNotContain("fetch first").doesNotContain("limit");
    assertThat(loaded.entitiesLoaded).isEqualTo(POSITIONS + POSITIONS * ENTRIES_EACH);
    assertThat(warnings.hhh90003004()).hasSize(1);
    record("join fetch + order by entry + setMaxResults(10)", loaded);
    EVIDENCE.append("  warning: %s%n".formatted(warnings.hhh90003004().getFirst()));
  }

  /**
   * Hibernate 7.4.5: the subquery rewrite above is decided when a query string is first
   * interpreted, and the interpretation is cached per session factory. Run the same {@code join
   * fetch} once without a page, and every later run of that string with {@code setMaxResults(10)}
   * returns all 100 positions: no subquery, no in-memory page, no warning. With {@code
   * hibernate.query.plan_cache_enabled=false} the page is 10 again. See
   * docs/decisions/06-persistence.md
   */
  @Test
  void reproducesPageIgnoredAfterTheSameQueryRanUnpaged() {
    Db fresh = new Db();
    Db noPlanCache = new Db("spring.jpa.properties.hibernate.query.plan_cache_enabled=false");
    try (Warnings warnings = new Warnings()) {
      Loaded unpaged =
          fresh.measure(em -> em.createQuery(SHARED, LoanPositionEntity.class).getResultList());
      Loaded paged =
          fresh.measure(
              em ->
                  em.createQuery(SHARED, LoanPositionEntity.class)
                      .setMaxResults(10)
                      .getResultList());

      assertThat(unpaged.positions).isEqualTo(POSITIONS);
      assertThat(paged.positions).isEqualTo(POSITIONS);
      assertThat(paged.sql.getFirst()).doesNotContain("fetch first");
      assertThat(warnings.hhh90003004()).isEmpty();
      record("same string, unpaged run first, then page of 10", paged);

      noPlanCache.measure(em -> em.createQuery(SHARED, LoanPositionEntity.class).getResultList());
      Loaded uncached =
          noPlanCache.measure(
              em ->
                  em.createQuery(SHARED, LoanPositionEntity.class)
                      .setMaxResults(10)
                      .getResultList());
      assertThat(uncached.positions).isEqualTo(10);
      record("same, plan_cache_enabled=false", uncached);
    } finally {
      fresh.context.close();
      noPlanCache.context.close();
    }
  }

  /** The other order: a page first does not truncate a later unpaged run. */
  @Test
  void anUnpagedRunAfterAPagedOneIsComplete() {
    Db fresh = new Db();
    try {
      Loaded paged =
          fresh.measure(
              em ->
                  em.createQuery(SHARED, LoanPositionEntity.class)
                      .setMaxResults(10)
                      .getResultList());
      Loaded unpaged =
          fresh.measure(em -> em.createQuery(SHARED, LoanPositionEntity.class).getResultList());

      assertThat(paged.positions).isEqualTo(10);
      unpaged.assertComplete(POSITIONS);
      record("same string, page of 10 first, then unpaged", unpaged);
    } finally {
      fresh.context.close();
    }
  }

  @Test
  void theStrictSettingRefusesInsteadOfPagingInMemory() {
    Throwable thrown = catchFrom(() -> strict.measure(NPlusOneIT::pageOrderedByAnEntryColumn));

    assertThat(thrown).isInstanceOf(org.hibernate.HibernateException.class);
    assertThat(thrown).hasMessageContaining("fail_on_pagination_over_collection_fetch");
    EVIDENCE.append(
        "%-48s | %s: %s%n"
            .formatted(
                "same, fail_on_pagination_over_collection_fetch",
                thrown.getClass().getName(),
                thrown.getMessage()));
  }

  private static List<LoanPositionEntity> pageOrderedByAnEntryColumn(EntityManager em) {
    return em.createQuery(
            "select p from LoanPositionEntity p join fetch p.entries e"
                + " order by p.id, e.businessDate",
            LoanPositionEntity.class)
        .setMaxResults(10)
        .getResultList();
  }

  /** Captures Hibernate's log output while open. */
  private static final class Warnings implements AutoCloseable {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    Warnings() {
      appender.start();
      root.addAppender(appender);
    }

    List<String> hhh90003004() {
      return appender.list.stream()
          .map(ILoggingEvent::getFormattedMessage)
          .filter(message -> message.contains("HHH90003004"))
          .toList();
    }

    @Override
    public void close() {
      root.detachAppender(appender);
    }
  }

  /** Page the ids with {@code limit}, then fetch those positions with their entries. */
  @Test
  void twoQueryPaginationIsCorrect() {
    Loaded loaded =
        defaults.measure(
            em -> {
              List<String> ids =
                  em.createQuery(
                          "select p.id from LoanPositionEntity p order by p.id", String.class)
                      .setMaxResults(10)
                      .getResultList();
              return em.createQuery(
                      "select distinct p from LoanPositionEntity p join fetch p.entries"
                          + " where p.id in :ids order by p.id",
                      LoanPositionEntity.class)
                  .setParameter("ids", ids)
                  .getResultList();
            });

    assertThat(loaded.statements).isEqualTo(2);
    assertThat(loaded.positions).isEqualTo(10);
    assertThat(loaded.entries).isEqualTo(10 * ENTRIES_EACH);
    assertThat(loaded.sql.getFirst()).containsIgnoringCase("fetch first");
    assertThat(loaded.firstIds).isEqualTo("LP-0001..LP-0010");
    record("ids page with limit, then join fetch by ids", loaded);
  }

  private static Throwable catchFrom(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException e) {
      return e;
    }
    throw new AssertionError("expected a failure");
  }

  private static void record(String variant, Loaded loaded) {
    EVIDENCE.append(
        "%-48s | statements=%d positions=%d entries=%d entitiesLoaded=%d%n"
            .formatted(
                variant,
                loaded.statements,
                loaded.positions,
                loaded.entries,
                loaded.entitiesLoaded));
    for (int i = 0; i < Math.min(2, loaded.sql.size()); i++) {
      EVIDENCE.append("  sql[%d]: %s%n".formatted(i, loaded.sql.get(i)));
    }
    if (loaded.sql.size() > 2) {
      EVIDENCE.append("  … %d more%n".formatted(loaded.sql.size() - 2));
    }
  }

  /** What one variant loaded, and what it cost. */
  record Loaded(
      long statements,
      int positions,
      int entries,
      long entitiesLoaded,
      List<String> sql,
      String firstIds) {

    void assertComplete(int expectedPositions) {
      assertThat(positions).isEqualTo(expectedPositions);
      assertThat(entries).isEqualTo(expectedPositions * ENTRIES_EACH);
    }
  }

  /** One context, one database, the same fixture. */
  static final class Db {

    final ConfigurableApplicationContext context;
    final TransactionTemplate tx;
    final EntityManagerFactory emf;
    final Statistics statistics;

    Db(String... properties) {
      String[] all = new String[properties.length + 1];
      all[0] = SqlRecorder.PROPERTY;
      System.arraycopy(properties, 0, all, 1, properties.length);
      context = LedgerDb.start(all);
      tx = context.getBean(TransactionTemplate.class);
      emf = context.getBean(EntityManagerFactory.class);
      statistics = emf.unwrap(SessionFactoryImplementor.class).getStatistics();
      new LedgerFixtures(context.getBean(JdbcClient.class))
          .counterparty("CP-001")
          .security("HK0000000001", "41.26")
          .positions("LP-", POSITIONS, "CP-001", "HK0000000001")
          .entriesForAll("LP-", LocalDate.of(2026, 9, 1), ENTRIES_EACH, "0.41");
    }

    /**
     * Runs the query, then touches every position's entries inside the same transaction, the way a
     * caller building a report would. Everything is counted from the query on.
     */
    Loaded measure(Function<EntityManager, List<LoanPositionEntity>> query) {
      return tx.execute(
          status -> {
            EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(emf);
            statistics.clear();
            SqlRecorder.clear();
            List<LoanPositionEntity> positions = query.apply(em);
            int entries = 0;
            for (LoanPositionEntity position : positions) {
              for (AccrualEntryEntity entry : position.entries()) {
                assertThat(entry.businessDate()).isNotNull();
                entries++;
              }
            }
            String firstIds =
                positions.isEmpty()
                    ? ""
                    : positions.getFirst().id() + ".." + positions.getLast().id();
            return new Loaded(
                statistics.getPrepareStatementCount(),
                positions.size(),
                entries,
                entitiesLoaded(),
                SqlRecorder.statements(),
                firstIds);
          });
    }

    /** Entities hydrated from result sets: positions plus entries, whether returned or not. */
    private long entitiesLoaded() {
      return statistics.getEntityLoadCount();
    }
  }
}
