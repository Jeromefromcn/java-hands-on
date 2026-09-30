package com.jerome.slledger.adapter.persistence.accrual;

import com.jerome.slledger.adapter.persistence.loan.AccrualRuleColumns;
import com.jerome.slledger.adapter.persistence.support.MoneyColumns;
import com.jerome.slledger.application.accrual.AccrualCandidate;
import com.jerome.slledger.application.accrual.AccrualCandidates;
import com.jerome.slledger.application.accrual.PartitionScheme;
import com.jerome.slledger.application.accrual.Slice;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanStatus;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Keyset pages of the positions an accrual covers, joined to the security's price.
 *
 * <p>Every row is mapped inside a {@code try}: a row that cannot be restored comes back as {@link
 * AccrualCandidate.Corrupt} instead of failing the page. The columns are read raw first (a
 * primitive can always be read) and only then turned into domain types, so the failure is the
 * mapping's and never the driver's.
 */
@Repository
public class JdbcAccrualCandidates implements AccrualCandidates {

  /**
   * The trade condition is repeated on the trade's own position id ({@code cursorOnTrade}): without
   * it Postgres merges the ordered positions with the whole ordered index of trades and reads every
   * trade entry below the cursor for each page, so a page costs more the deeper the scan is. With
   * it the merge starts at the cursor. Measured in docs/evidence/11-chunk-size.txt.
   */
  private static String active(String cursorOnTrade) {
    return " FROM loan_position p JOIN security s ON s.isin = p.isin"
        + " WHERE p.status <> 'CLOSED'"
        + " AND EXISTS (SELECT 1 FROM loan_trade t WHERE t.position_id = p.id"
        + " AND t.direction = 'BORROW' AND t.settlement_date <= :date"
        + cursorOnTrade
        + ")";
  }

  private static final String ACTIVE = active("");

  /** Shared with the revaluation reader: the same row, mapped by the same code. */
  public static final String COLUMNS =
      "SELECT p.id, p.counterparty_id, p.isin, p.original_quantity, p.outstanding_quantity,"
          + " p.status, p.collateral_amount, p.collateral_currency, p.rule_type, p.flat_rate,"
          + " s.market_price_amount, s.market_price_currency";

  private final JdbcClient jdbc;

  JdbcAccrualCandidates(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<AccrualCandidate> page(
      LocalDate businessDate, Slice slice, Optional<String> afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(COLUMNS)
            .append(
                active(
                    (afterId.isPresent() ? " AND t.position_id > :after" : "")
                        + (slice.idFrom().isPresent() ? " AND t.position_id >= :from" : "")
                        + (slice.idTo().isPresent() ? " AND t.position_id <= :to" : "")));
    Map<String, Object> params = new HashMap<>();
    params.put("date", businessDate);
    slice
        .counterparty()
        .ifPresent(
            cp -> {
              sql.append(" AND p.counterparty_id = :cp");
              params.put("cp", cp);
            });
    slice
        .idFrom()
        .ifPresent(
            from -> {
              sql.append(" AND p.id >= :from");
              params.put("from", from);
            });
    slice
        .idTo()
        .ifPresent(
            to -> {
              sql.append(" AND p.id <= :to");
              params.put("to", to);
            });
    afterId.ifPresent(
        after -> {
          sql.append(" AND p.id > :after");
          params.put("after", after);
        });
    sql.append(" ORDER BY p.id LIMIT :limit");
    params.put("limit", limit);
    return jdbc.sql(sql.toString()).params(params).query((rs, row) -> candidate(rs)).list();
  }

  @Override
  public List<Slice> slices(PartitionScheme scheme, LocalDate businessDate, int parts) {
    return switch (scheme) {
      case NONE -> List.of(Slice.ALL);
      case COUNTERPARTY ->
          jdbc
              .sql("SELECT DISTINCT p.counterparty_id" + ACTIVE + " ORDER BY p.counterparty_id")
              .param("date", businessDate)
              .query(String.class)
              .list()
              .stream()
              .map(Slice::ofCounterparty)
              .toList();
      case BALANCED -> balanced(businessDate, parts);
    };
  }

  private List<Slice> balanced(LocalDate businessDate, int parts) {
    List<Slice> slices = new ArrayList<>();
    jdbc.sql(
            "SELECT min(id) AS lo, max(id) AS hi FROM ("
                + "SELECT p.id, ntile(:parts) OVER (ORDER BY p.id) AS bucket"
                + ACTIVE
                + ") ranked GROUP BY bucket ORDER BY bucket")
        .param("parts", parts)
        .param("date", businessDate)
        .query(
            (rs, row) -> {
              slices.add(Slice.ofIds(rs.getString("lo"), rs.getString("hi")));
              return null;
            })
        .list();
    return slices;
  }

  public static AccrualCandidate candidate(ResultSet rs) throws SQLException {
    String id = rs.getString("id");
    String counterparty = rs.getString("counterparty_id");
    String isin = rs.getString("isin");
    long original = rs.getLong("original_quantity");
    long outstanding = rs.getLong("outstanding_quantity");
    String status = rs.getString("status");
    BigDecimal collateralAmount = rs.getBigDecimal("collateral_amount");
    String collateralCurrency = rs.getString("collateral_currency");
    String ruleType = rs.getString("rule_type");
    BigDecimal flatRate = rs.getBigDecimal("flat_rate");
    BigDecimal priceAmount = rs.getBigDecimal("market_price_amount");
    String priceCurrency = rs.getString("market_price_currency");
    try {
      LoanPosition position =
          LoanPosition.restore(
              new LoanPositionId(id),
              new CounterpartyId(counterparty),
              new Isin(isin.trim()),
              original,
              outstanding,
              LoanStatus.valueOf(status),
              MoneyColumns.read(collateralAmount, collateralCurrency),
              AccrualRuleColumns.toRule(ruleType, flatRate));
      Money price = MoneyColumns.read(priceAmount, priceCurrency);
      return new AccrualCandidate.Valid(position, price);
    } catch (RuntimeException e) {
      return new AccrualCandidate.Corrupt(id, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }
}
