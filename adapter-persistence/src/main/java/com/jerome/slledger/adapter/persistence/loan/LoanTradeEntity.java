package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.domain.loan.TradeDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The persistence model of {@code LoanTrade}. The surrogate id is the table's, not the domain's: a
 * trade is identified in {@code domain} by nothing, and found by its idempotency key.
 */
@Entity
@Table(name = "loan_trade")
public class LoanTradeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  String idempotencyKey;

  String positionId;

  String counterpartyId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 12)
  String isin;

  @Enumerated(EnumType.STRING)
  TradeDirection direction;

  long quantity;

  @Column(precision = 22, scale = 6)
  BigDecimal unitPriceAmount;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 3)
  String unitPriceCurrency;

  LocalDate tradeDate;

  LocalDate settlementDate;

  protected LoanTradeEntity() {}
}
