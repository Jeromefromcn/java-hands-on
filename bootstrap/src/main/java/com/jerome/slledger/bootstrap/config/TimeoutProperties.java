package com.jerome.slledger.bootstrap.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The timeouts this service owns or declares, in one place so that {@link TimeoutBudget} can check
 * them against each other. The source's connect and read timeouts ({@code ledger.marketdata.*}) and
 * the fan-out deadline ({@code ledger.marketdata.executor.deadline}) keep their stage 7 and 8 homes
 * and are read from there.
 *
 * @param edge the deadline the caller or the gateway in front of this service applies. It is
 *     declared, not enforced here: nothing in the process can make a client wait less, but the
 *     budget is only meaningful against it
 * @param dbStatement the longest any statement may run; applied to every connection as {@code SET
 *     statement_timeout}, so the database cancels it instead of finishing work nobody waits for
 * @param poolAcquire how long a request waits for a pooled connection before failing
 * @param enforce whether a budget that does not hold refuses start-up. Off only for the tests that
 *     reproduce the unbounded waits of stages 7 and 8
 */
@Validated
@ConfigurationProperties("ledger.timeouts")
public record TimeoutProperties(
    @DefaultValue("30s") @NotNull Duration edge,
    @DefaultValue("10s") @NotNull Duration dbStatement,
    @DefaultValue("3s") @NotNull Duration poolAcquire,
    @DefaultValue("true") boolean enforce) {

  public static TimeoutProperties defaults() {
    return new TimeoutProperties(
        Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(3), true);
  }
}
