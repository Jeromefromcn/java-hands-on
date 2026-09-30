package labs.concurrency;

import com.jerome.slledger.domain.money.Money;
import java.util.Currency;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * One shared balance credited by N threads: a lock-free compare-and-set loop against a
 * {@code synchronized} method. Both go through {@link Money#plus}, so both check the currency on
 * every update. Run once with one thread and once with more threads than a core can run, by JMH's
 * {@code -t}: the contention level is the variable.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class BalanceBenchmark {

  private static final Money CENT = Money.of("0.01", Currency.getInstance("HKD"));

  private AtomicReference<Money> cas;
  private Money locked;

  @Setup
  public void setUp() {
    cas = new AtomicReference<>(Money.zero(Currency.getInstance("HKD")));
    locked = Money.zero(Currency.getInstance("HKD"));
  }

  @Benchmark
  public Money casCredit() {
    Money current;
    Money next;
    do {
      current = cas.get();
      next = current.plus(CENT);
    } while (!cas.compareAndSet(current, next));
    return next;
  }

  @Benchmark
  public Money synchronizedCredit() {
    return credit();
  }

  private synchronized Money credit() {
    locked = locked.plus(CENT);
    return locked;
  }
}
