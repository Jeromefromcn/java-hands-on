package com.jerome.slledger.domain.accrual;

import com.jerome.slledger.domain.money.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Rates that change as the outstanding value grows, charged <strong>marginally</strong>: each slice
 * of the value is priced at its own tier's rate.
 *
 * <p>"Marginal" is in the class name rather than only in this paragraph, because the alternative
 * shape — a cliff, where crossing a threshold reprices the whole value at the higher rate — is a
 * plausible reading of the same rate card and the difference is not small. On tiers of 3.65 percent
 * up to a million and 7.30 percent above it, two million accrues 300 a day marginally and 400 a day
 * at a cliff: a third more, silently, on a class that had been called TieredRate. A cliff
 * implementation, if a contract ever needs one, is a sibling of this class rather than a mode of it
 * — a boolean constructor argument that changes a fee by a third is not a configuration option.
 */
public record MarginalTieredRate(NavigableMap<BigDecimal, Rate> tiers) implements AccrualRule {

  public MarginalTieredRate {
    Objects.requireNonNull(tiers, "tiers");
    if (tiers.isEmpty()) {
      throw new IllegalArgumentException("A tiered rate needs at least one tier");
    }
    if (tiers.firstKey().signum() != 0) {
      throw new IllegalArgumentException(
          "The lowest tier must start at zero, or values below %s would be unpriced"
              .formatted(tiers.firstKey().toPlainString()));
    }
    // Two jobs in one line. The defensive copy, because a NavigableMap the caller still holds is
    // a rate card someone can edit after a position was opened against it. And the key
    // canonicalisation, because TreeMap compares BigDecimal keys with compareTo while
    // BigDecimal.hashCode is scale-sensitive: without stripping, a card written with 1000000 and
    // one written with 1000000.00 are equal maps with different hash codes, so this record's
    // generated equals and hashCode would disagree and a rule put in a HashSet could not be found
    // again. Exactly the contract IdentityContractTest reproduces breaking.
    TreeMap<BigDecimal, Rate> canonical = new TreeMap<>();
    for (Map.Entry<BigDecimal, Rate> tier : tiers.entrySet()) {
      canonical.put(tier.getKey().stripTrailingZeros(), tier.getValue());
    }
    tiers = Collections.unmodifiableNavigableMap(canonical);
  }

  @Override
  public BigDecimal annualFee(BigDecimal outstandingValue, LocalDate on) {
    BigDecimal annualFee = BigDecimal.ZERO;
    BigDecimal sliceTop = outstandingValue;

    for (Map.Entry<BigDecimal, Rate> tier : tiers.descendingMap().entrySet()) {
      BigDecimal threshold = tier.getKey();
      if (sliceTop.compareTo(threshold) <= 0) {
        continue;
      }
      // Multiplication only, so the annual figure is exact. The one division is the day count's.
      annualFee = annualFee.add(sliceTop.subtract(threshold).multiply(tier.getValue().annual()));
      sliceTop = threshold;
    }

    return annualFee;
  }
}
