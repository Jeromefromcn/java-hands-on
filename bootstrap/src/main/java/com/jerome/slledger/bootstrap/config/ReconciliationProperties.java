package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.application.reconciliation.ReconPolicy;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The reconciliation's thresholds. The defaults are synthetic: a person running a real book sets
 * them from what the custodian's rounding actually looks like.
 *
 * @param absoluteTolerance a value difference up to this many currency units is accepted as
 *     rounding
 * @param relativeTolerance or up to this fraction of the ledger's value, whichever is larger
 * @param settlementWindowDays days after settling before a position the custodian lacks is a break
 * @param retryDelay the wait between resubmissions of a settlement the custodian lacks
 * @param maxAttempts resubmissions before a break goes to a person
 */
@Validated
@ConfigurationProperties(prefix = "ledger.reconciliation")
public record ReconciliationProperties(
    @DefaultValue("1.00") @NotNull @DecimalMin("0.00") BigDecimal absoluteTolerance,
    @DefaultValue("0.0001") @NotNull @DecimalMin("0.0") BigDecimal relativeTolerance,
    @DefaultValue("2") @Min(0) int settlementWindowDays,
    @DefaultValue("4h") @NotNull java.time.Duration retryDelay,
    @DefaultValue("3") @Min(1) int maxAttempts) {

  ReconPolicy policy() {
    return new ReconPolicy(absoluteTolerance, relativeTolerance, settlementWindowDays);
  }
}
