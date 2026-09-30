package com.jerome.slledger.adapter.persistence.saga;

import com.jerome.slledger.application.saga.CreditReservations;
import com.jerome.slledger.application.saga.SagaRefusedException;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import com.jerome.slledger.domain.party.CreditLimit;
import java.math.BigDecimal;
import java.util.Currency;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Credit reservations, with the same shape as the collateral hold: the saga id is the primary key,
 * a release with no reserve leaves a mark, and a reserve is refused after a release.
 *
 * <p>Reserving takes the counterparty's row {@code FOR UPDATE} first. That serialises every
 * reservation for one counterparty, so two sagas each see the other's reservation and the limit is
 * checked against the truth. It does not serialise a plain {@code OpenPosition}, which does its own
 * unlocked credit check (stage 8's finding, still true); a saga is the path that closes the race.
 */
@Repository
public class JdbcCreditReservations implements CreditReservations {

  private static final String RESERVED = "RESERVED";
  private static final String CONSUMED = "CONSUMED";
  private static final String RELEASED = "RELEASED";

  private final JdbcClient jdbc;

  JdbcCreditReservations(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void reserve(String sagaId, CounterpartyId counterparty, Money amount) {
    requireTransaction();
    // Serialise this counterparty's reservations before looking at anything they change.
    CreditLimit limit =
        jdbc.sql(
                "SELECT credit_limit_amount, credit_limit_currency FROM counterparty WHERE id = ?"
                    + " FOR UPDATE")
            .param(counterparty.value())
            .query(
                (rs, row) ->
                    new CreditLimit(
                        Money.of(
                            rs.getBigDecimal("credit_limit_amount"),
                            Currency.getInstance(rs.getString("credit_limit_currency").trim()))))
            .optional()
            .orElseThrow(() -> new SagaRefusedException("No counterparty " + counterparty.value()));

    boolean inserted =
        jdbc.sql(
                    "INSERT INTO credit_reservation (saga_id, counterparty_id, amount, currency, status)"
                        + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (saga_id) DO NOTHING")
                .params(
                    sagaId,
                    counterparty.value(),
                    amount.amount(),
                    amount.currency().getCurrencyCode(),
                    RESERVED)
                .update()
            == 1;
    if (!inserted) {
      String status =
          jdbc.sql("SELECT status FROM credit_reservation WHERE saga_id = ? FOR UPDATE")
              .param(sagaId)
              .query(String.class)
              .single();
      if (RELEASED.equals(status)) {
        throw new SagaRefusedException("Saga " + sagaId + " has already released its credit");
      }
      return; // RESERVED or CONSUMED: a repeat
    }

    // Everything counted against the limit except this row: positions and other reservations.
    Currency currency = amount.currency();
    BigDecimal positions =
        jdbc.sql(
                "SELECT coalesce(sum(collateral_amount), 0) FROM loan_position WHERE counterparty_id = ?"
                    + " AND status <> 'CLOSED' AND collateral_currency = ?")
            .params(counterparty.value(), currency.getCurrencyCode())
            .query(BigDecimal.class)
            .single();
    BigDecimal reserved =
        jdbc.sql(
                "SELECT coalesce(sum(amount), 0) FROM credit_reservation WHERE counterparty_id = ?"
                    + " AND currency = ? AND status = ? AND saga_id <> ?")
            .params(counterparty.value(), currency.getCurrencyCode(), RESERVED, sagaId)
            .query(BigDecimal.class)
            .single();
    limit.checkCanAbsorb(Money.of(positions.add(reserved), currency), amount);
  }

  @Override
  public void consume(String sagaId) {
    requireTransaction();
    jdbc.sql("UPDATE credit_reservation SET status = ? WHERE saga_id = ? AND status = ?")
        .params(CONSUMED, sagaId, RESERVED)
        .update();
  }

  @Override
  public void release(String sagaId) {
    requireTransaction();
    jdbc.sql(
            "INSERT INTO credit_reservation (saga_id, status) VALUES (?, ?)"
                + " ON CONFLICT (saga_id) DO UPDATE SET status = ? WHERE credit_reservation.status = ?")
        .params(sagaId, RELEASED, RELEASED, RESERVED)
        .update();
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "A credit reservation must be changed inside the caller's transaction");
    }
  }
}
