package com.jerome.slledger.adapter.persistence.party;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The persistence model of {@code Counterparty}. Only {@link CounterpartyMapper} touches it. */
@Entity
@Table(name = "counterparty")
public class CounterpartyEntity {

  @Id String id;

  String name;

  @Column(precision = 22, scale = 6)
  BigDecimal creditLimitAmount;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 3)
  String creditLimitCurrency;

  protected CounterpartyEntity() {}
}
