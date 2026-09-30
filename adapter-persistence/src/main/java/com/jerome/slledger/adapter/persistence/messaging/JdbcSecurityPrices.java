package com.jerome.slledger.adapter.persistence.messaging;

import com.jerome.slledger.application.messaging.SecurityPrices;
import com.jerome.slledger.application.position.UnknownReferenceException;
import com.jerome.slledger.domain.error.CurrencyMismatchException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import java.util.Currency;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The version gate, in SQL. The row is read {@code FOR UPDATE}, so two updates of one security are
 * serialised and each is compared against the version the other left, not against a stale read.
 *
 * <p>Refuses to run outside a transaction: the read-compare-write is only a gate while the row lock
 * is held from the read to the write.
 */
@Repository
public class JdbcSecurityPrices implements SecurityPrices {

  private record Stored(Currency currency, long version) {}

  private final JdbcClient jdbc;

  JdbcSecurityPrices(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean applyIfNewer(Isin security, Money price, long version) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "A price update must be applied inside a transaction: the row lock is the gate");
    }
    Optional<Stored> stored =
        jdbc.sql("SELECT currency, price_version FROM security WHERE isin = ? FOR UPDATE")
            .param(security.value())
            .query(
                (rs, row) ->
                    new Stored(
                        Currency.getInstance(rs.getString("currency")),
                        rs.getLong("price_version")))
            .optional();
    Stored current =
        stored.orElseThrow(() -> UnknownReferenceException.subject("security", security.value()));
    if (!current.currency().equals(price.currency())) {
      throw new CurrencyMismatchException(current.currency(), price.currency());
    }
    if (version <= current.version()) {
      return false;
    }
    jdbc.sql(
            "UPDATE security SET market_price_amount = ?, market_price_currency = ?,"
                + " price_version = ? WHERE isin = ?")
        .params(price.amount(), price.currency().getCurrencyCode(), version, security.value())
        .update();
    return true;
  }
}
