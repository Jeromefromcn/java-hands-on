package com.jerome.slledger.application.quote;

import com.jerome.slledger.application.context.RequestContext;
import com.jerome.slledger.application.port.MarketDataRejectedException;
import com.jerome.slledger.application.port.MarketDataUnavailableException;
import com.jerome.slledger.application.position.UnknownReferenceException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Prices many securities at once: one task per security on a bounded executor, joined under a
 * single deadline.
 *
 * <p>Three rules, each learned from a failure in {@code docs/decisions/08-concurrency.md}:
 *
 * <ul>
 *   <li>Every {@code supplyAsync} names its executor. Without one the task runs on {@code
 *       ForkJoinPool.commonPool()}, which the rest of the JVM shares and which this service does
 *       not size.
 *   <li>Every stage's failure is turned into that security's outcome. A stage whose future nobody
 *       reads fails silently.
 *   <li>One security failing, timing out or being refused never discards the others.
 *   <li>Each task carries the submitter's {@link RequestContext}; a pooled thread never inherits
 *       one by accident.
 * </ul>
 *
 * <p>Not transactional: each task reads the security in its own short read and then waits on the
 * source with no connection held, as {@link QuotePrice} does.
 */
public class PriceFanOut {

  private static final Log log = LogFactory.getLog(PriceFanOut.class);

  private final QuotePrice quote;
  private final Executor executor;
  private final Duration deadline;

  public PriceFanOut(QuotePrice quote, Executor executor, Duration deadline) {
    this.quote = quote;
    this.executor = executor;
    this.deadline = deadline;
  }

  /**
   * One outcome per distinct security, in first-seen order.
   *
   * @throws OverloadedException if the executor refused every one of them
   */
  public List<QuoteOutcome> quote(List<Isin> isins) {
    List<Isin> distinct = new ArrayList<>(new LinkedHashSet<>(isins));
    List<CompletableFuture<QuoteOutcome>> pending = new ArrayList<>();
    for (Isin isin : distinct) {
      pending.add(submit(isin));
    }
    long limit = System.nanoTime() + deadline.toNanos();
    List<QuoteOutcome> outcomes = new ArrayList<>();
    for (int i = 0; i < distinct.size(); i++) {
      outcomes.add(await(distinct.get(i), pending.get(i), limit));
    }
    if (!outcomes.isEmpty()
        && outcomes.stream()
            .allMatch(
                o ->
                    o instanceof QuoteOutcome.Failed f
                        && f.kind() == QuoteOutcome.Kind.OVERLOADED)) {
      throw new OverloadedException(
          "The market data executor refused all " + outcomes.size() + " requests");
    }
    return outcomes;
  }

  /** Prices grouped by currency; amounts in different currencies are never added together. */
  public static Map<Currency, List<Money>> groupByCurrency(List<QuoteOutcome> outcomes) {
    Map<Currency, List<Money>> byCurrency =
        new TreeMap<>(java.util.Comparator.comparing(Currency::getCurrencyCode));
    for (QuoteOutcome outcome : outcomes) {
      if (outcome instanceof QuoteOutcome.Priced priced) {
        byCurrency
            .computeIfAbsent(priced.price().currency(), c -> new ArrayList<>())
            .add(priced.price());
      }
    }
    return byCurrency;
  }

  private CompletableFuture<QuoteOutcome> submit(Isin isin) {
    try {
      return CompletableFuture.supplyAsync(
              RequestContext.propagating(() -> quote.quote(isin)), executor)
          .handle((price, failure) -> outcome(isin, price, failure));
    } catch (RejectedExecutionException refused) {
      return CompletableFuture.completedFuture(
          new QuoteOutcome.Failed(isin, QuoteOutcome.Kind.OVERLOADED));
    }
  }

  private QuoteOutcome await(Isin isin, CompletableFuture<QuoteOutcome> pending, long limit) {
    try {
      return pending.get(Math.max(0, limit - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (TimeoutException late) {
      // Cancelling completes the future; it does not stop the worker, which stays in the source's
      // read until the source answers or the client's read timeout fires.
      pending.cancel(true);
      return new QuoteOutcome.Failed(isin, QuoteOutcome.Kind.TIMED_OUT);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new QuoteOutcome.Failed(isin, QuoteOutcome.Kind.TIMED_OUT);
    } catch (ExecutionException e) {
      // handle() turned every failure into an outcome, so this is unreachable in practice.
      log.error("Unexpected failure pricing " + isin.value(), e.getCause());
      return new QuoteOutcome.Failed(isin, QuoteOutcome.Kind.ERROR);
    }
  }

  private static QuoteOutcome outcome(Isin isin, Money price, Throwable failure) {
    if (failure == null) {
      return new QuoteOutcome.Priced(isin, price);
    }
    Throwable cause =
        failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause()
            : failure;
    return switch (cause) {
      case UnknownReferenceException e -> new QuoteOutcome.Failed(isin, QuoteOutcome.Kind.UNKNOWN);
      case MarketDataUnavailableException e ->
          new QuoteOutcome.Failed(isin, QuoteOutcome.Kind.UNAVAILABLE);
      case MarketDataRejectedException e ->
          new QuoteOutcome.Failed(isin, QuoteOutcome.Kind.REJECTED);
      default -> {
        log.error(
            "Unexpected failure pricing "
                + isin.value()
                + RequestContext.current().map(c -> " [trace " + c.traceId() + "]").orElse(""),
            cause);
        yield new QuoteOutcome.Failed(isin, QuoteOutcome.Kind.ERROR);
      }
    };
  }
}
