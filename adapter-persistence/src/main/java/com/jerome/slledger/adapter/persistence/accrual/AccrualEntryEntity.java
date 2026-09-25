package com.jerome.slledger.adapter.persistence.accrual;

import com.jerome.slledger.adapter.persistence.loan.LoanPositionEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The persistence model of {@code AccrualEntry}. Its key is the table's primary key, one entry per
 * position per day.
 *
 * <p>{@link #position} is the owning side of {@code LoanPositionEntity.entries}, read-only: the
 * column is written through {@link #positionId}, so an entry can be stored knowing only the id its
 * domain counterpart carries.
 */
@Entity
@Table(name = "accrual_entry")
@IdClass(AccrualEntryId.class)
public class AccrualEntryEntity {

  // Named explicitly: the join column below maps the same physical column, and Hibernate refuses
  // two logical names (positionId, position_id) for one column.
  @Id
  @Column(name = "position_id")
  String positionId;

  @Id LocalDate businessDate;

  @Column(precision = 22, scale = 6)
  BigDecimal feeAmount;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 3)
  String feeCurrency;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "position_id", insertable = false, updatable = false)
  LoanPositionEntity position;

  protected AccrualEntryEntity() {}

  public LocalDate businessDate() {
    return businessDate;
  }
}
