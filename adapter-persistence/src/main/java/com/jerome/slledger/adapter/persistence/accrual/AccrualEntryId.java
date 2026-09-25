package com.jerome.slledger.adapter.persistence.accrual;

import java.io.Serializable;
import java.time.LocalDate;

/** The composite key of {@link AccrualEntryEntity}; JPA requires an id class to be Serializable. */
public record AccrualEntryId(String positionId, LocalDate businessDate) implements Serializable {}
