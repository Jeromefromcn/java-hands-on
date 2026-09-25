package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.adapter.persistence.accrual.AccrualEntryEntity;
import com.jerome.slledger.domain.loan.LoanStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The persistence model of {@code LoanPosition}. Only {@link LoanPositionMapper} and this package's
 * repositories touch it.
 *
 * <p>{@link #entries} exists on this side only. In {@code domain} an accrual entry is outside the
 * position aggregate and refers to it by id; here the association is kept, read-only and lazy, for
 * the queries that want positions with their entries. It never reaches {@code domain}.
 */
@Entity
@Table(name = "loan_position")
public class LoanPositionEntity {

  @Id String id;

  String counterpartyId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 12)
  String isin;

  long originalQuantity;

  long outstandingQuantity;

  @Enumerated(EnumType.STRING)
  LoanStatus status;

  @Column(precision = 22, scale = 6)
  BigDecimal collateralAmount;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 3)
  String collateralCurrency;

  String ruleType;

  BigDecimal flatRate;

  @OneToMany(mappedBy = "position", fetch = FetchType.LAZY)
  List<AccrualEntryEntity> entries = new ArrayList<>();

  protected LoanPositionEntity() {}

  public String id() {
    return id;
  }

  public List<AccrualEntryEntity> entries() {
    return entries;
  }
}
