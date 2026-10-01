package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.adapter.web.client.MarketDataProperties;
import com.jerome.slledger.adapter.web.client.ResilienceProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Every timeout on a request's path, and the rule that holds them together: a layer that waits on
 * others must wait longer than the sum of what it waits for. If it does not, the layer gives up
 * while the work below it is still running; the caller retries, and the source or the database now
 * does the work twice, three times, at once. {@link #verify()} states the chains and refuses the
 * configuration where one is broken.
 *
 * <p>A timeout of zero or none is an unbounded wait, which is longer than anything and so breaks
 * the chain it is in. The table is printed at start-up so the numbers are read together, once.
 */
public final class TimeoutBudget {

  /** A layer's worst-case time on its own, and the layers it calls one after another. */
  record Layer(String name, Duration own, List<Layer> calls) {

    static Layer of(String name, Duration own, Layer... calls) {
      return new Layer(name, own, List.of(calls));
    }

    Duration sumOfCalls() {
      Duration sum = Duration.ZERO;
      for (Layer call : calls) {
        sum = sum.plus(call.own());
      }
      return sum;
    }
  }

  private static final Duration UNBOUNDED = Duration.ofDays(365_000);

  private final List<String> chainNames = new ArrayList<>();
  private final List<Layer> roots = new ArrayList<>();
  private final List<String> extraProblems = new ArrayList<>();

  public TimeoutBudget(
      TimeoutProperties timeouts,
      MarketDataProperties source,
      MarketDataExecutorProperties executor,
      ResilienceProperties resilience) {
    Duration connect = bounded(source.connectTimeout());
    Duration read = bounded(source.readTimeout());
    Layer sourceCall =
        Layer.of(
            "market data source (connect " + connect + " + read " + read + ")", connect.plus(read));
    Layer database =
        Layer.of(
            "database (pool acquire "
                + timeouts.poolAcquire()
                + " + statement "
                + timeouts.dbStatement()
                + ")",
            timeouts.poolAcquire().plus(timeouts.dbStatement()));
    chain(
        "GET /quotes",
        Layer.of(
            "edge",
            timeouts.edge(),
            Layer.of("fan-out deadline", executor.deadline(), sourceCall)));
    chain("GET /securities/{isin}/quote", Layer.of("edge", timeouts.edge(), sourceCall));
    chain("ledger reads and writes", Layer.of("edge", timeouts.edge(), database));
    if (resilience.enabled() && !resilience.breaker().slowCall().minus(read).isNegative()) {
      extraProblems.add(
          "breaker slow-call threshold "
              + resilience.breaker().slowCall()
              + " is not below the source read timeout "
              + read
              + ": a slow call would time out before it could be counted as slow");
    }
  }

  private void chain(String name, Layer root) {
    chainNames.add(name);
    roots.add(root);
  }

  private static Duration bounded(Duration timeout) {
    return timeout == null || timeout.isZero() ? UNBOUNDED : timeout;
  }

  /** Every way the configuration breaks the rule; empty when it holds. */
  public List<String> problems() {
    List<String> problems = new ArrayList<>(extraProblems);
    for (int i = 0; i < roots.size(); i++) {
      check(chainNames.get(i), roots.get(i), problems);
    }
    return problems;
  }

  private static void check(String chain, Layer layer, List<String> problems) {
    if (!layer.calls().isEmpty() && !layer.own().minus(layer.sumOfCalls()).isPositive()) {
      problems.add(
          chain
              + ": "
              + layer.name()
              + " waits "
              + show(layer.own())
              + " but what it calls can take "
              + show(layer.sumOfCalls())
              + "; it would give up while the work below it is still running");
    }
    for (Layer call : layer.calls()) {
      check(chain, call, problems);
    }
  }

  /**
   * @throws IllegalStateException naming each broken chain
   */
  public void verify() {
    List<String> problems = problems();
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "The timeout budget does not hold:\n  - " + String.join("\n  - ", problems));
    }
  }

  /** The chains, outermost first, as text for the start-up log and the evidence file. */
  public String table() {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < roots.size(); i++) {
      out.append(chainNames.get(i)).append('\n');
      append(out, roots.get(i), 1);
    }
    return out.toString();
  }

  private static void append(StringBuilder out, Layer layer, int depth) {
    out.append("  ".repeat(depth))
        .append(show(layer.own()))
        .append("  ")
        .append(layer.name())
        .append('\n');
    for (Layer call : layer.calls()) {
      append(out, call, depth + 1);
    }
  }

  private static String show(Duration d) {
    return d.compareTo(UNBOUNDED) >= 0 ? "unbounded" : d.toString();
  }
}
