package com.jerome.slledger.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.application.port.MarketDataUnavailableException;
import com.jerome.slledger.application.port.SecurityDirectory;
import com.jerome.slledger.application.quote.PriceFanOut;
import com.jerome.slledger.application.quote.QuoteOutcome;
import com.jerome.slledger.application.quote.QuotePrice;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.instrument.Security;
import com.jerome.slledger.domain.money.Money;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The same load through each of the four rejection policies: one worker, a queue of one, and six
 * price requests to a source that answers only after its own timeout. What each policy does to the
 * submitter, to the tasks and to the caller's thread is the finding. See
 * docs/decisions/08-concurrency.md.
 */
@Timeout(60)
class RejectionPolicyMatrixTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final long SOURCE_MILLIS = 500;
  private static final long DEADLINE_MILLIS = 300;
  private static final StringBuilder EVIDENCE = new StringBuilder();

  private record Row(
      MarketDataExecutorProperties.Policy policy,
      Map<QuoteOutcome.Kind, Integer> kinds,
      int priced,
      List<String> executedOn,
      long elapsedMillis,
      long rejected) {}

  @AfterAll
  static void write() {
    Evidence.write(
        "08-rejection-policies.txt",
        """
        Stage 08 · section 5 — the four rejection policies (RejectionPolicyMatrixTest)

        Load, identical for every policy: core=1, max=1, queue=1; six price requests submitted in
        one fan-out; a source that holds each call for %d ms and then fails (its "read timeout");
        the fan-out's batch deadline is %d ms. Outcome counts are per kind. "executed on" lists
        the distinct threads that actually ran a call to the source. "elapsed" is how long the
        fan-out call took on the submitting thread.

        %s"""
            .formatted(SOURCE_MILLIS, DEADLINE_MILLIS, EVIDENCE));
  }

  @Test
  @DisplayName("abort, caller-runs, discard and discard-oldest under the same load")
  void fourPolicies() throws Exception {
    List<Row> rows = new ArrayList<>();
    for (MarketDataExecutorProperties.Policy policy :
        MarketDataExecutorProperties.Policy.values()) {
      rows.add(run(policy));
    }
    for (Row row : rows) {
      EVIDENCE.append(
          "%-15s outcomes=%s priced=%d rejected(counter)=%d%n                executed on=%s%n"
              .formatted(
                  row.policy(), row.kinds(), row.priced(), row.rejected(), row.executedOn()));
      EVIDENCE.append("                elapsed=%d ms%n%n".formatted(row.elapsedMillis()));
    }

    Row abort = rows.get(0);
    Row callerRuns = rows.get(1);
    Row discard = rows.get(2);
    Row discardOldest = rows.get(3);

    // abort: two admitted, four refused loudly, and the submitter is not held.
    assertThat(abort.kinds()).containsEntry(QuoteOutcome.Kind.OVERLOADED, 4);
    assertThat(abort.executedOn()).allMatch(name -> name.startsWith("market-data-"));
    assertThat(abort.elapsedMillis()).isLessThan(SOURCE_MILLIS + 200);

    // caller-runs: nothing is refused; the submitting thread runs source calls itself, so the
    // batch deadline cannot cover them and the call takes several source timeouts.
    assertThat(callerRuns.kinds()).doesNotContainKey(QuoteOutcome.Kind.OVERLOADED);
    assertThat(callerRuns.executedOn()).anyMatch(name -> !name.startsWith("market-data-"));
    assertThat(callerRuns.elapsedMillis()).isGreaterThanOrEqualTo(2 * SOURCE_MILLIS);

    // discard and discard-oldest: dropped tasks have no outcome of their own. They surface only
    // as TIMED_OUT at the deadline, indistinguishable from a slow source.
    assertThat(discard.kinds()).doesNotContainKey(QuoteOutcome.Kind.OVERLOADED);
    assertThat(discard.kinds()).containsEntry(QuoteOutcome.Kind.TIMED_OUT, 6);
    assertThat(discardOldest.kinds()).doesNotContainKey(QuoteOutcome.Kind.OVERLOADED);
    assertThat(discardOldest.kinds()).containsEntry(QuoteOutcome.Kind.TIMED_OUT, 6);
    // Both count the drops as rejections, so the metric still sees them.
    assertThat(discard.rejected()).isEqualTo(4);
    assertThat(discardOldest.rejected()).isEqualTo(4);
  }

  private static Row run(MarketDataExecutorProperties.Policy policy) throws Exception {
    List<String> threads = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch never = new CountDownLatch(1);
    SecurityDirectory directory =
        isin -> Optional.of(new Security(isin, "Synthetic", HKD, Money.zero(HKD)));
    QuotePrice quote =
        new QuotePrice(
            directory,
            isin -> {
              threads.add(Thread.currentThread().getName());
              try {
                never.await(SOURCE_MILLIS, TimeUnit.MILLISECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              throw new MarketDataUnavailableException("source timed out", null);
            });
    MarketDataExecutorProperties props =
        new MarketDataExecutorProperties(
            1,
            1,
            1,
            policy,
            MarketDataExecutorProperties.Type.PLATFORM,
            1,
            Duration.ofMillis(DEADLINE_MILLIS));
    try (MarketDataPool pool = new MarketDataPool(props)) {
      PriceFanOut fanOut = new PriceFanOut(quote, pool, props.deadline());
      List<Isin> isins = new ArrayList<>();
      for (int i = 1; i <= 6; i++) {
        isins.add(new Isin("HK000000010" + i));
      }
      long started = System.nanoTime();
      List<QuoteOutcome> outcomes;
      try {
        outcomes = fanOut.quote(isins);
      } finally {
        never.countDown();
      }
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      Map<QuoteOutcome.Kind, Integer> kinds = new EnumMap<>(QuoteOutcome.Kind.class);
      int priced = 0;
      for (QuoteOutcome outcome : outcomes) {
        if (outcome instanceof QuoteOutcome.Failed failed) {
          kinds.merge(failed.kind(), 1, Integer::sum);
        } else {
          priced++;
        }
      }
      assertThat(outcomes).as("an outcome for every ISIN under " + policy).hasSize(6);
      List<String> executedOn;
      synchronized (threads) {
        executedOn = List.copyOf(new TreeSet<>(threads));
      }
      return new Row(policy, kinds, priced, executedOn, elapsed, pool.stats().rejected());
    }
  }
}
