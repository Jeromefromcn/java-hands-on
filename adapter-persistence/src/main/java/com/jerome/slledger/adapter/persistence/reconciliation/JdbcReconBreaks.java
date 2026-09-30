package com.jerome.slledger.adapter.persistence.reconciliation;

import com.jerome.slledger.adapter.persistence.support.MoneyColumns;
import com.jerome.slledger.application.reconciliation.ReconBreak;
import com.jerome.slledger.application.reconciliation.ReconBreaks;
import com.jerome.slledger.domain.money.Money;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code INSERT ... ON CONFLICT DO NOTHING} on (snapshot, position): one break per position per
 * snapshot.
 */
@Repository
public class JdbcReconBreaks implements ReconBreaks {

  private final JdbcClient jdbc;

  JdbcReconBreaks(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int save(List<ReconBreak> breaks) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "Breaks must be recorded inside the transaction of their chunk");
    }
    int inserted = 0;
    for (ReconBreak b : breaks) {
      inserted +=
          jdbc.sql(
                  "INSERT INTO recon_break (snapshot_id, position_id, kind, disposition,"
                      + " business_date, our_quantity, their_quantity, our_value_amount,"
                      + " our_value_currency, their_value_amount, their_value_currency, detail,"
                      + " next_attempt_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                      + " CASE WHEN ? = 'RETRY_SETTLEMENT' THEN now() END)"
                      + " ON CONFLICT (snapshot_id, position_id) DO NOTHING")
              .params(
                  b.snapshot(),
                  b.positionId(),
                  b.kind().name(),
                  b.disposition().name(),
                  b.businessDate(),
                  b.ourQuantity().orElse(null),
                  b.theirQuantity().orElse(null),
                  b.ourValue().map(MoneyColumns::amountOf).orElse(null),
                  b.ourValue().map(MoneyColumns::currencyOf).orElse(null),
                  b.theirValue().map(MoneyColumns::amountOf).orElse(null),
                  b.theirValue().map(MoneyColumns::currencyOf).orElse(null),
                  b.detail(),
                  b.disposition().name())
              .update();
    }
    return inserted;
  }

  /** For tests and the ladder: money columns back to {@link Money}. */
  static Optional<Money> money(java.math.BigDecimal amount, String currency) {
    return amount == null ? Optional.empty() : Optional.of(MoneyColumns.read(amount, currency));
  }
}
