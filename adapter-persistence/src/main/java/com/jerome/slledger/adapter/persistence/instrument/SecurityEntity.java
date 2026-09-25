package com.jerome.slledger.adapter.persistence.instrument;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The persistence model of {@code Security}. Only {@link SecurityMapper} touches it. */
@Entity
@Table(name = "security")
public class SecurityEntity {

  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 12)
  String isin;

  String name;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 3)
  String currency;

  @Column(precision = 22, scale = 6)
  BigDecimal marketPriceAmount;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 3)
  String marketPriceCurrency;

  protected SecurityEntity() {}
}
