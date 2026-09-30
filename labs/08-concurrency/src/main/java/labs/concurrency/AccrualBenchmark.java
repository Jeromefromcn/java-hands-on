package labs.concurrency;

import com.jerome.slledger.domain.accrual.AccrualRule;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.money.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The daily fee, in two representations: {@code BigDecimal} (what the ledger uses) and {@code
 * double} (what stage 2's drift test shows it must not use).
 *
 * <p>The {@code mistake*} benchmarks are the classic errors kept on purpose: inputs are compile-time
 * constants and the result is dropped. The JIT may fold the constants and remove the computation,
 * and JMH then reports the time of an empty method. The {@code consumed*} benchmarks read their
 * inputs from state and hand the result to a {@link Blackhole}.
 */
public class AccrualBenchmark {

  private static final AccrualRule CONSTANT_RULE = new FlatRate(Rate.of("0.0365"));
  private static final BigDecimal CONSTANT_VALUE = new BigDecimal("1234567.89");
  private static final LocalDate DAY = LocalDate.of(2026, 9, 1);
  private static final double CONSTANT_VALUE_D = 1234567.89;
  private static final double CONSTANT_RATE_D = 0.0365;

  @State(Scope.Thread)
  public static class Inputs {
    AccrualRule rule;
    BigDecimal value;
    double valueD;
    double rateD;

    @Setup
    public void setUp() {
      rule = new FlatRate(Rate.of("0.0365"));
      value = new BigDecimal("1234567.89");
      valueD = 1234567.89;
      rateD = 0.0365;
    }
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public void mistakeBigDecimalDiscarded() {
    CONSTANT_RULE.dailyFee(CONSTANT_VALUE, DAY);
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public void mistakeDoubleDiscarded() {
    double fee = CONSTANT_VALUE_D * CONSTANT_RATE_D / 365.0;
    if (fee < 0) { // never true; kept so the mistake is "computed and looked at", not "unused"
      throw new IllegalStateException();
    }
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public void consumedBigDecimal(Inputs in, Blackhole bh) {
    bh.consume(in.rule.dailyFee(in.value, DAY));
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public void consumedDouble(Inputs in, Blackhole bh) {
    bh.consume(in.valueD * in.rateD / 365.0);
  }
}
