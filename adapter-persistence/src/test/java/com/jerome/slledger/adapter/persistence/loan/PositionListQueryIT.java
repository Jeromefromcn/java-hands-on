package com.jerome.slledger.adapter.persistence.loan;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.adapter.persistence.testing.LedgerFixtures;
import com.jerome.slledger.adapter.persistence.testing.SqlRecorder;
import com.jerome.slledger.application.port.PositionListQuery;
import com.jerome.slledger.application.port.PositionPage;
import com.jerome.slledger.domain.money.Money;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercise 7: the position listing through JPA and through {@code JdbcClient}, page by page, must
 * be equal, money included.
 */
class PositionListQueryIT {

  private static final int POSITIONS = 57;
  private static final StringBuilder EVIDENCE = new StringBuilder();

  private static ConfigurableApplicationContext context;
  private static TransactionTemplate tx;
  private static PositionListQuery jpa;
  private static PositionListQuery jdbc;

  @BeforeAll
  static void start() {
    context = LedgerDb.start(new String[] {SqlRecorder.PROPERTY});
    tx = context.getBean(TransactionTemplate.class);
    tx.setReadOnly(true);
    JdbcClient client = context.getBean(JdbcClient.class);
    new LedgerFixtures(client)
        .counterparty("CP-001")
        .security("HK0000000001", "41.26")
        .positions("LP-", POSITIONS - 3, "CP-001", "HK0000000001");
    // Currencies of scale 0 and 3, and a partially returned one, among the rest.
    client
        .sql(
            "INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                + " rule_type, flat_rate) VALUES"
                + " ('LP-0005a', 'CP-001', 'HK0000000001', 100, 40, 'PARTIALLY_RETURNED',"
                + " 1000.00, 'HKD', 'FLAT', 0.0365),"
                + " ('LP-0020a', 'CP-001', 'HK0000000001', 100, 100, 'OPEN', 150000, 'JPY',"
                + " 'FLAT', 0.0365),"
                + " ('LP-0040a', 'CP-001', 'HK0000000001', 100, 100, 'OPEN', 12.345, 'BHD',"
                + " 'FLAT', 0.0365)")
        .update();
    jpa = new JpaPositionListQuery(context.getBean(EntityManager.class));
    jdbc = new JdbcPositionListQuery(client);
  }

  @AfterAll
  static void stop() throws IOException {
    EVIDENCE.append(linesOfCode());
    Evidence.write("06-jpa-vs-jdbc.txt", EVIDENCE.toString());
    context.close();
  }

  @ParameterizedTest(name = "page size {0}")
  @ValueSource(ints = {10, 25})
  void everyPageIsEqualThroughBoth(int size) {
    int pages = (POSITIONS + size - 1) / size;
    List<String> ids = new ArrayList<>();
    for (int page = 0; page < pages; page++) {
      int p = page;
      PositionPage viaJpa = tx.execute(s -> jpa.page(Optional.empty(), p, size));
      PositionPage viaJdbc = tx.execute(s -> jdbc.page(Optional.empty(), p, size));

      assertThat(viaJdbc).isEqualTo(viaJpa);
      assertThat(viaJpa.totalRows()).isEqualTo(POSITIONS);
      viaJpa.rows().forEach(row -> ids.add(row.id().value()));
    }
    assertThat(ids).hasSize(POSITIONS).doesNotHaveDuplicates().isSorted();
    // The last page is the partial one.
    int last = pages - 1;
    assertThat(tx.execute(s -> jpa.page(Optional.empty(), last, size)).rows())
        .hasSize(POSITIONS - last * size);
    Set<Money> collateral =
        tx.execute(s -> jdbc.page(Optional.empty(), 0, POSITIONS)).rows().stream()
            .map(row -> row.collateral())
            .collect(Collectors.toSet());
    assertThat(collateral)
        .contains(
            Money.of("150000", java.util.Currency.getInstance("JPY")),
            Money.of("12.345", java.util.Currency.getInstance("BHD")));
  }

  @Test
  void theGeneratedSqlOfEach() {
    SqlRecorder.clear();
    tx.execute(s -> jpa.page(Optional.empty(), 2, 10));
    EVIDENCE.append(
        "JPA (JPQL constructor projection), page 2 of size 10, as Hibernate sent it:%n"
            .formatted());
    SqlRecorder.statements().forEach(sql -> EVIDENCE.append("  ").append(sql).append('\n'));
    EVIDENCE.append(
        "JdbcClient, as written:%n  %s%n  %s%n%n"
            .formatted(JdbcPositionListQuery.PAGE_SQL, JdbcPositionListQuery.COUNT_SQL));
    assertThat(SqlRecorder.statements()).hasSize(2);
  }

  @Test
  void thePropertySelectsTheJdbcImplementation() {
    try (var selected = LedgerDb.start(new String[] {"ledger.persistence.list-query=jdbc"})) {
      assertThat(selected.getBeansOfType(PositionListQuery.class).values())
          .singleElement()
          .satisfies(
              bean ->
                  assertThat(org.springframework.aop.support.AopUtils.getTargetClass(bean))
                      .isEqualTo(JdbcPositionListQuery.class));
    }
  }

  /** Lines that are code: not blank, not an import or package line, not a comment. */
  private static String linesOfCode() throws IOException {
    Path dir = Path.of("src/main/java/com/jerome/slledger/adapter/persistence/loan");
    StringBuilder out =
        new StringBuilder("Lines of code (no blanks, imports, package, comments):\n");
    for (String file :
        List.of(
            "JpaPositionListQuery.java", "PositionRowColumns.java", "JdbcPositionListQuery.java")) {
      out.append("  %-28s %d%n".formatted(file, count(Files.readAllLines(dir.resolve(file)))));
    }
    return out.toString();
  }

  private static long count(List<String> lines) {
    long code = 0;
    boolean inComment = false;
    for (String raw : lines) {
      String line = raw.strip();
      if (inComment) {
        if (line.contains("*/")) {
          inComment = false;
        }
        continue;
      }
      if (line.startsWith("/*")) {
        inComment = !line.contains("*/");
        continue;
      }
      if (line.isEmpty()
          || line.startsWith("//")
          || line.startsWith("import ")
          || line.startsWith("package ")) {
        continue;
      }
      code++;
    }
    return code;
  }
}
