package com.jerome.slledger.adapter.persistence.reconciliation;

import com.jerome.slledger.adapter.persistence.support.MoneyColumns;
import com.jerome.slledger.application.reconciliation.HoldingComparison;
import com.jerome.slledger.application.reconciliation.HoldingPair;
import com.jerome.slledger.domain.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The ledger's active positions on a date, full outer joined to a snapshot on the position id, by
 * keyset on that id. Each side is filtered to ids after the cursor before the join, so a page does
 * not depend on how deep it is.
 *
 * <p>Active on the date is as for the accrual: not closed, and its opening trade settled on or
 * before it. A position whose line was refused as malformed (a {@code batch_skip} row of the
 * reconciliation for this snapshot) is left out on both sides.
 */
@Repository
public class JdbcHoldingComparison implements HoldingComparison {

  private final JdbcClient jdbc;

  JdbcHoldingComparison(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<HoldingPair> page(
      String snapshot, LocalDate businessDate, Optional<String> afterId, int limit) {
    String ourCursor = afterId.isPresent() ? " AND p.id > :after" : "";
    String theirCursor = afterId.isPresent() ? " AND position_id > :after" : "";
    String sql =
        "SELECT coalesce(o.pid, c.position_id) AS pid,"
            + " o.pid IS NOT NULL AS has_ours, o.qty, o.price_amount, o.price_currency, o.settled_on,"
            + " c.position_id IS NOT NULL AS has_theirs, c.quantity AS their_qty,"
            + " c.value_amount, c.value_currency"
            + " FROM (SELECT p.id AS pid, p.outstanding_quantity AS qty,"
            + " s.market_price_amount AS price_amount, s.market_price_currency AS price_currency,"
            + " (SELECT min(t.settlement_date) FROM loan_trade t WHERE t.position_id = p.id"
            + " AND t.direction = 'BORROW') AS settled_on"
            + " FROM loan_position p JOIN security s ON s.isin = p.isin"
            + " WHERE p.status <> 'CLOSED'"
            + ourCursor
            + ") o"
            + " FULL OUTER JOIN (SELECT * FROM custodian_holding WHERE snapshot_id = :snapshot"
            + theirCursor
            + ") c ON c.position_id = o.pid"
            + " WHERE (o.pid IS NULL OR o.settled_on <= :date)"
            + " AND NOT EXISTS (SELECT 1 FROM batch_skip k WHERE k.job = 'reconciliation'"
            + " AND k.scope = :snapshot AND k.item_key = coalesce(o.pid, c.position_id))"
            + " ORDER BY pid LIMIT :limit";
    Map<String, Object> params = new HashMap<>();
    params.put("snapshot", snapshot);
    params.put("date", businessDate);
    params.put("limit", limit);
    afterId.ifPresent(after -> params.put("after", after));
    return jdbc.sql(sql).params(params).query((rs, n) -> pair(rs)).list();
  }

  private static HoldingPair pair(ResultSet rs) throws SQLException {
    String id = rs.getString("pid");
    boolean hasOurs = rs.getBoolean("has_ours");
    boolean hasTheirs = rs.getBoolean("has_theirs");
    HoldingPair.Ours ours = null;
    if (hasOurs) {
      try {
        Money price =
            MoneyColumns.read(rs.getBigDecimal("price_amount"), rs.getString("price_currency"));
        long quantity = rs.getLong("qty");
        ours =
            new HoldingPair.Ours(
                id,
                quantity,
                price.multiply(BigDecimal.valueOf(quantity), RoundingMode.HALF_UP),
                rs.getObject("settled_on", LocalDate.class));
      } catch (RuntimeException e) {
        return new HoldingPair.Unreadable(id, e.getClass().getSimpleName() + ": " + e.getMessage());
      }
    }
    HoldingPair.Theirs theirs = null;
    if (hasTheirs) {
      theirs =
          new HoldingPair.Theirs(
              id,
              rs.getLong("their_qty"),
              MoneyColumns.read(rs.getBigDecimal("value_amount"), rs.getString("value_currency")));
    }
    if (ours != null && theirs != null) {
      return new HoldingPair.Both(ours, theirs);
    }
    return ours != null ? new HoldingPair.OursOnly(ours) : new HoldingPair.TheirsOnly(theirs);
  }
}
