package com.jerome.slledger.adapter.persistence.loan;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.adapter.persistence.testing.LedgerFixtures;
import com.jerome.slledger.application.port.PositionCursorQuery;
import com.jerome.slledger.application.port.PositionListQuery;
import com.jerome.slledger.application.port.PositionPage;
import com.jerome.slledger.application.port.PositionRow;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.party.CounterpartyId;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** The counterparty filter lives in the SQL: page, total and keyset walk, through both adapters. */
class ScopedPositionQueryIT {

  private static final CounterpartyId B = new CounterpartyId("CP-002");

  private static ConfigurableApplicationContext context;
  private static TransactionTemplate tx;
  private static PositionListQuery jpa;
  private static PositionListQuery jdbc;
  private static PositionCursorQuery cursor;

  @BeforeAll
  static void start() {
    context = LedgerDb.start();
    tx = context.getBean(TransactionTemplate.class);
    tx.setReadOnly(true);
    JdbcClient client = context.getBean(JdbcClient.class);
    new LedgerFixtures(client)
        .counterparty("CP-001")
        .counterparty("CP-002")
        .security("HK0000000001", "41.26")
        .positions("A-", 7, "CP-001", "HK0000000001")
        .positions("B-", 4, "CP-002", "HK0000000001");
    jpa = new JpaPositionListQuery(context.getBean(EntityManager.class));
    jdbc = new JdbcPositionListQuery(client);
    cursor = new JdbcPositionCursorQuery(client);
  }

  @AfterAll
  static void stop() {
    context.close();
  }

  @Test
  @DisplayName("page and total hold only the counterparty's rows, and both adapters agree")
  void pageAndTotal() {
    PositionPage viaJpa = tx.execute(s -> jpa.page(Optional.of(B), 0, 3));
    PositionPage viaJdbc = tx.execute(s -> jdbc.page(Optional.of(B), 0, 3));

    assertThat(viaJdbc).isEqualTo(viaJpa);
    assertThat(viaJpa.totalRows()).isEqualTo(4);
    assertThat(viaJpa.rows()).hasSize(3).allMatch(row -> row.counterparty().equals(B));
    assertThat(tx.execute(s -> jpa.page(Optional.of(B), 1, 3)).rows()).hasSize(1);
    assertThat(tx.execute(s -> jpa.page(Optional.empty(), 0, 100)).totalRows()).isEqualTo(11);
    assertThat(tx.execute(s -> jpa.page(Optional.of(new CounterpartyId("CP-404")), 0, 3)).rows())
        .isEmpty();
  }

  @Test
  @DisplayName("the keyset walk of one counterparty visits its rows once, in order")
  void keysetWalk() {
    List<String> ids = new ArrayList<>();
    Optional<LoanPositionId> next = Optional.empty();
    do {
      Optional<LoanPositionId> from = next;
      var slice = tx.execute(s -> cursor.after(Optional.of(B), from, 3));
      slice.rows().stream().map(PositionRow::id).forEach(id -> ids.add(id.value()));
      next = slice.next();
    } while (next.isPresent());

    assertThat(ids).hasSize(4).allMatch(id -> id.startsWith("B-")).isSorted();
  }
}
