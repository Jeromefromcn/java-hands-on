package com.jerome.slledger.application.position;

import com.jerome.slledger.domain.accrual.AccrualRule;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.accrual.FloatingRate;
import com.jerome.slledger.domain.accrual.MarginalTieredRate;
import java.util.stream.Collectors;

/** A rule's canonical text, for a request fingerprint. */
final class AccrualRules {

  private AccrualRules() {}

  static String canonical(AccrualRule rule) {
    return switch (rule) {
      case FlatRate flat -> "FLAT:" + flat.rate().annual().toPlainString();
      case FloatingRate floating ->
          "FLOATING:"
              + floating.reference().getClass().getName()
              + ":"
              + floating.spread().annual().toPlainString();
      case MarginalTieredRate tiered ->
          "TIERED:"
              + tiered.tiers().entrySet().stream()
                  .map(
                      e -> e.getKey().toPlainString() + "=" + e.getValue().annual().toPlainString())
                  .collect(Collectors.joining(","));
      default ->
          throw new IllegalArgumentException(
              "no canonical form for " + rule.getClass().getSimpleName());
    };
  }
}
