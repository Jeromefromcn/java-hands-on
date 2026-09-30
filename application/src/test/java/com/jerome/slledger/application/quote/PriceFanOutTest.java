package com.jerome.slledger.application.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jerome.slledger.application.port.MarketDataUnavailableException;
import com.jerome.slledger.application.port.MarketPriceSource;
import com.jerome.slledger.application.port.SecurityDirectory;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.instrument.Security;
import com.jerome.slledger.domain.money.Money;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class PriceFanOutTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Currency USD = Currency.getInstance("USD");
  private static final Isin A = new Isin("HK0000000001");
  private static final Isin B = new Isin("HK0000000002");
  private static final Isin C = new Isin("US0000000001");

  private final ExecutorService pool = Executors.newFixedThreadPool(4);
  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void tearDown() {
    release.countDown();
    pool.shutdownNow();
  }

  /** Securities A and B trade in HKD, C in USD; the source is whatever the test says. */
  private static QuotePrice quotePrice(MarketPriceSource source) {
    SecurityDirectory directory =
        isin -> {
          Currency currency = isin.equals(C) ? USD : HKD;
          return Optional.of(
              new Security(isin, "Synthetic " + isin.value(), currency, Money.zero(currency)));
        };
    return new QuotePrice(directory, source);
  }

  private static Money priceIn(Isin isin) {
    return isin.equals(C) ? Money.of("10.00", USD) : Money.of("41.26", HKD);
  }

  @Test
  @DisplayName("one failing security does not discard the others")
  void oneFailureKeepsTheOthers() {
    PriceFanOut fanOut =
        new PriceFanOut(
            quotePrice(
                isin -> {
                  if (isin.equals(B)) {
                    throw new MarketDataUnavailableException("down", null);
                  }
                  return priceIn(isin);
                }),
            pool,
            Duration.ofSeconds(5));

    List<QuoteOutcome> outcomes = fanOut.quote(List.of(A, B, C));

    assertThat(outcomes)
        .containsExactly(
            new QuoteOutcome.Priced(A, priceIn(A)),
            new QuoteOutcome.Failed(B, QuoteOutcome.Kind.UNAVAILABLE),
            new QuoteOutcome.Priced(C, priceIn(C)));
  }

  @Test
  @DisplayName("the same ISIN twice is fetched once")
  void duplicateIsinsAreFetchedOnce() {
    AtomicInteger calls = new AtomicInteger();
    PriceFanOut fanOut =
        new PriceFanOut(
            quotePrice(
                isin -> {
                  calls.incrementAndGet();
                  return priceIn(isin);
                }),
            pool,
            Duration.ofSeconds(5));

    assertThat(fanOut.quote(List.of(A, A, B, A))).hasSize(2);
    assertThat(calls).hasValue(2);
  }

  @Test
  @DisplayName("a refused submission is OVERLOADED for that ISIN only")
  void rejectedSubmissionBecomesOverloadedForThatIsinOnly() {
    AtomicInteger submitted = new AtomicInteger();
    PriceFanOut fanOut =
        new PriceFanOut(
            quotePrice(PriceFanOutTest::priceIn),
            task -> {
              if (submitted.incrementAndGet() == 2) {
                throw new RejectedExecutionException("full");
              }
              pool.execute(task);
            },
            Duration.ofSeconds(5));

    assertThat(fanOut.quote(List.of(A, B, C)))
        .containsExactly(
            new QuoteOutcome.Priced(A, priceIn(A)),
            new QuoteOutcome.Failed(B, QuoteOutcome.Kind.OVERLOADED),
            new QuoteOutcome.Priced(C, priceIn(C)));
  }

  @Test
  @DisplayName("when every ISIN is refused the whole batch is overloaded")
  void everyRefusalIsOverloaded() {
    PriceFanOut fanOut =
        new PriceFanOut(
            quotePrice(PriceFanOutTest::priceIn),
            task -> {
              throw new RejectedExecutionException("full");
            },
            Duration.ofSeconds(5));

    assertThatThrownBy(() -> fanOut.quote(List.of(A, B))).isInstanceOf(OverloadedException.class);
  }

  @Test
  @DisplayName("a security that outlasts the deadline is TIMED_OUT; the others still answer")
  void deadlineTurnsSlowIsinIntoTimedOut() {
    PriceFanOut fanOut =
        new PriceFanOut(
            quotePrice(
                isin -> {
                  if (isin.equals(B)) {
                    await(release);
                  }
                  return priceIn(isin);
                }),
            pool,
            Duration.ofMillis(300));

    assertThat(fanOut.quote(List.of(A, B)))
        .containsExactly(
            new QuoteOutcome.Priced(A, priceIn(A)),
            new QuoteOutcome.Failed(B, QuoteOutcome.Kind.TIMED_OUT));
  }

  @Test
  @DisplayName("prices in two currencies are grouped, not added")
  void mixedCurrenciesAreGroupedNotSummed() {
    PriceFanOut fanOut =
        new PriceFanOut(quotePrice(PriceFanOutTest::priceIn), pool, Duration.ofSeconds(5));

    Map<Currency, List<Money>> grouped =
        PriceFanOut.groupByCurrency(fanOut.quote(List.of(A, B, C)));

    assertThat(grouped).containsOnlyKeys(HKD, USD);
    assertThat(grouped.get(HKD)).hasSize(2);
    assertThat(grouped.get(USD)).containsExactly(priceIn(C));
  }

  @Test
  @DisplayName("cancelling at the deadline does not free the worker")
  void cancelledFutureStillHoldsItsWorker() throws Exception {
    ThreadPoolExecutor single = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
    try {
      PriceFanOut fanOut =
          new PriceFanOut(
              quotePrice(
                  isin -> {
                    await(release);
                    return priceIn(isin);
                  }),
              single,
              Duration.ofMillis(200));

      assertThat(fanOut.quote(List.of(A)))
          .containsExactly(new QuoteOutcome.Failed(A, QuoteOutcome.Kind.TIMED_OUT));
      assertThat(single.getActiveCount()).isEqualTo(1);

      release.countDown();
      awaitTrue(() -> single.getActiveCount() == 0);
    } finally {
      single.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "supplyAsync with no executor runs on the common pool, and a full common pool starves it")
  void reproducesCommonPoolUsedWhenNoExecutorGiven() throws Exception {
    // See docs/decisions/08-concurrency.md
    int parallelism = ForkJoinPool.commonPool().getParallelism();
    // With parallelism 1 or less supplyAsync silently falls back to a thread per task.
    assumeTrue(parallelism > 1, "the common pool is not used on a single-CPU machine");
    AtomicReference<String> thread = new AtomicReference<>();
    CompletableFuture.supplyAsync(
            () -> {
              thread.set(Thread.currentThread().getName());
              return 1;
            })
        .get(10, TimeUnit.SECONDS);
    assertThat(thread.get()).startsWith("ForkJoinPool.commonPool-worker-");

    CountDownLatch busy = new CountDownLatch(parallelism);
    for (int i = 0; i < parallelism; i++) {
      CompletableFuture.runAsync(
          () -> {
            busy.countDown();
            await(release);
          });
    }
    assertThat(busy.await(10, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<Integer> unrelated = CompletableFuture.supplyAsync(() -> 2);
    assertThatThrownBy(() -> unrelated.get(500, TimeUnit.MILLISECONDS))
        .isInstanceOf(TimeoutException.class);
    release.countDown();
    assertThat(unrelated.get(10, TimeUnit.SECONDS)).isEqualTo(2);
  }

  @Test
  @DisplayName("a stage nobody joins fails without a trace; the fan-out surfaces the same failure")
  void reproducesExceptionSwallowedWithoutJoin() {
    // See docs/decisions/08-concurrency.md
    CompletableFuture<Integer> unobserved =
        CompletableFuture.supplyAsync(
            () -> {
              throw new MarketDataUnavailableException("down", null);
            },
            pool);
    awaitTrue(unobserved::isDone);
    // Nothing was thrown to the submitting thread, and nothing logged: the failure is only in the
    // future, which nobody reads.
    assertThat(unobserved.isCompletedExceptionally()).isTrue();

    PriceFanOut fanOut =
        new PriceFanOut(
            quotePrice(
                isin -> {
                  throw new MarketDataUnavailableException("down", null);
                }),
            pool,
            Duration.ofSeconds(5));
    assertThat(fanOut.quote(List.of(A)))
        .containsExactly(new QuoteOutcome.Failed(A, QuoteOutcome.Kind.UNAVAILABLE));
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(20, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Polls a condition with a bound; nothing is sequenced by time. */
  private static void awaitTrue(java.util.function.BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached");
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
    }
  }
}
