package com.jerome.slledger.adapter.persistence.loan;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.adapter.persistence.testing.LedgerFixtures;
import com.jerome.slledger.application.port.PositionListQuery;
import com.jerome.slledger.application.port.PositionPage;
import com.jerome.slledger.application.port.PositionRow;
import com.jerome.slledger.domain.party.CounterpartyId;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A listing filtered by a string a caller supplies. First written the way it is when it is written
 * in a hurry (concatenated), and broken with the payloads of every tutorial; then the real
 * adapters, which bind the value, with the same payloads.
 */
class SqlInjectionIT {

  /** Closes the string, makes the condition always true, comments out the rest. */
  private static final String LEAK = "x' OR '1'='1' --";

  /** Closes the string, runs a second statement, comments out the rest. */
  private static final String STACKED = "x'; UPDATE loan_position SET status = 'CLOSED'; --";

  /** The payload from the exercise: ends the string and the statement and comments out the rest. */
  private static final String BARE = "'; --";

  private static final StringBuilder EVIDENCE =
      new StringBuilder("Stage 13 · section 7 — SQL injection (SqlInjectionIT)\n\n");

  private static ConfigurableApplicationContext context;
  private static JdbcClient jdbc;
  private static TransactionTemplate tx;
  private static PositionListQuery jpa;
  private static PositionListQuery jdbcQuery;

  /** The listing as a hurried developer writes it. Test-only; see the decision record. */
  private static final class ConcatenatedSearch {
    static List<String> idsOf(JdbcClient jdbc, String counterparty) {
      return jdbc.sql("SELECT id FROM loan_position WHERE counterparty_id = '" + counterparty + "'")
          .query(String.class)
          .list();
    }
  }

  @BeforeAll
  static void start() {
    context = LedgerDb.start();
    jdbc = context.getBean(JdbcClient.class);
    tx = context.getBean(TransactionTemplate.class);
    new LedgerFixtures(jdbc)
        .counterparty("CP-001")
        .counterparty("CP-002")
        .security("HK0000000001", "41.26")
        .positions("A-", 3, "CP-001", "HK0000000001")
        .positions("B-", 2, "CP-002", "HK0000000001");
    jpa = new JpaPositionListQuery(context.getBean(EntityManager.class));
    jdbcQuery = new JdbcPositionListQuery(jdbc);
  }

  @AfterAll
  static void stop() {
    Evidence.write("13-sql-injection.txt", EVIDENCE.toString());
    context.close();
  }

  private static long closed() {
    return jdbc.sql("SELECT count(*) FROM loan_position WHERE status = 'CLOSED'")
        .query(Long.class)
        .single();
  }

  @Test
  @DisplayName("reproducesSqlInjectionByConcatenation")
  void reproducesSqlInjectionByConcatenation() {
    // See docs/decisions/13-security.md
    // CP-002 owns two positions of the five. A caller who names a counterparty gets those two, and
    // with the payload gets all five: the filter is part of the string the attacker writes.
    List<String> honest = ConcatenatedSearch.idsOf(jdbc, "CP-002");
    List<String> leaked = ConcatenatedSearch.idsOf(jdbc, LEAK);
    String bareOutcome;
    try {
      bareOutcome = ConcatenatedSearch.idsOf(jdbc, BARE).size() + " rows";
    } catch (DataAccessException e) {
      bareOutcome = "error: " + e.getMostSpecificCause().getMessage();
    }

    assertThat(honest).hasSize(2);
    assertThat(leaked).hasSize(5);
    // The exercise's payload alone ends the statement and comments out the closing quote. The
    // driver sees a second statement where the application meant one: the query is no longer the
    // one that was written, which is the whole point, and here it fails loudly. The leak and the
    // stacked statement below are the same break made to succeed.
    assertThat(bareOutcome).startsWith("error:");
    EVIDENCE
        .append("Concatenated query (test-only), counterparty_id = '<input>':\n")
        .append("  input CP-002           -> %d rows%n".formatted(honest.size()))
        .append("  input %s -> %d rows (every counterparty's)%n".formatted(LEAK, leaked.size()))
        .append("  input %s              -> %s%n".formatted(BARE, bareOutcome));

    // A second statement runs too. The caller is told the query failed (two result sets where one
    // was expected), after the driver has executed both: the error is not a rollback.
    long before = closed();
    try {
      ConcatenatedSearch.idsOf(jdbc, STACKED);
    } catch (DataAccessException expected) {
      // The statement was already executed when the driver noticed there were two.
    }
    long after = closed();
    assertThat(before).isZero();
    assertThat(after).isEqualTo(5);
    EVIDENCE.append(
        "  input %s -> positions CLOSED before %d, after %d%n%n".formatted(STACKED, before, after));
    jdbc.sql("UPDATE loan_position SET status = 'OPEN'").update();
  }

  @Test
  @DisplayName(
      "the real adapters bind the value: the same payloads find nothing and change nothing")
  void realAdaptersBindTheValue() {
    for (String payload : new String[] {LEAK, STACKED, BARE}) {
      CounterpartyId input = new CounterpartyId(payload);
      PositionPage viaJpa = tx.execute(s -> jpa.page(Optional.of(input), 0, 100));
      PositionPage viaJdbc = tx.execute(s -> jdbcQuery.page(Optional.of(input), 0, 100));

      assertThat(viaJpa.rows()).as(payload).isEmpty();
      assertThat(viaJpa.totalRows()).as(payload).isZero();
      assertThat(viaJdbc).as(payload).isEqualTo(viaJpa);
    }
    assertThat(closed()).isZero();
    PositionPage honest =
        tx.execute(s -> jdbcQuery.page(Optional.of(new CounterpartyId("CP-002")), 0, 100));
    assertThat(honest.rows()).extracting(PositionRow::id).hasSize(2);
    EVIDENCE.append(
        "Real adapters (JPA and JDBC), same three payloads as counterparty: 0 rows, total 0,\n"
            + "positions CLOSED afterwards: %d; counterparty CP-002: %d rows.%n"
                .formatted(closed(), honest.rows().size()));
  }
}
