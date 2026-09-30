package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.application.batch.RetentionPolicy;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How long finished rows are kept by the retention job.
 *
 * @param outbox published outbox rows
 * @param tombstones terminal TCC rows; must outlive {@code ledger.messaging.topic-retention}, which
 *     the configuration checks at start-up: a cancelled row that is purged before a late Try can
 *     arrive stops refusing it
 * @param sagas finished sagas and their reservations
 * @param batchSize rows deleted per transaction
 */
@Validated
@ConfigurationProperties(prefix = "ledger.retention")
public record RetentionProperties(
    @DefaultValue("7d") @NotNull Duration outbox,
    @DefaultValue("30d") @NotNull Duration tombstones,
    @DefaultValue("30d") @NotNull Duration sagas,
    @DefaultValue("5000") @Min(1) int batchSize) {

  RetentionPolicy policy() {
    return new RetentionPolicy(outbox, tombstones, sagas, batchSize);
  }
}
