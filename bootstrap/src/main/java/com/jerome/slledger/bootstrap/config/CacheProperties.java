package com.jerome.slledger.bootstrap.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The quote cache. Off unless {@code enabled} is true, so a profile that does not ask for it
 * behaves as stages 7 and 8 left it. Redis itself is configured with Spring Boot's own {@code
 * spring.data.redis.*}.
 *
 * <p>The worst staleness of a served price is {@code l2Ttl + l1Ttl}, each stretched by the jitter:
 * an entry copied out of the shared tier at the end of its life lives a full L1 TTL after that.
 * With the defaults that is 30 s + 5 s, and up to 42 s with a 20% jitter.
 */
@Validated
@ConfigurationProperties(prefix = "ledger.cache")
public record CacheProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("10000") @Min(1) long l1MaxSize,
    @DefaultValue("5s") @NotNull Duration l1Ttl,
    @DefaultValue("30s") @NotNull Duration l2Ttl,
    @DefaultValue("2s") @NotNull Duration negativeTtl,
    @DefaultValue("0.2") @DecimalMin("0.0") @DecimalMax(value = "1.0", inclusive = false)
        double jitter,
    @DefaultValue("10s") @NotNull Duration lockTtl,
    @DefaultValue("50") @Min(0) int lockPolls,
    @DefaultValue("20ms") @NotNull Duration lockPollInterval,
    @DefaultValue("sl:") @NotNull String keyPrefix,
    @DefaultValue("sl:price:invalidate") @NotNull String invalidationChannel) {

  @AssertTrue(message = "TTLs must be positive")
  public boolean isTtlsPositive() {
    return positive(l1Ttl) && positive(l2Ttl) && positive(negativeTtl) && positive(lockTtl);
  }

  @AssertTrue(message = "the L1 TTL must not exceed the L2 TTL")
  public boolean isL1NotLongerThanL2() {
    return l1Ttl.compareTo(l2Ttl) <= 0;
  }

  private static boolean positive(Duration duration) {
    return duration != null && !duration.isZero() && !duration.isNegative();
  }
}
