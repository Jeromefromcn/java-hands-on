package com.jerome.slledger.bootstrap.timing;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class TimingBeanPostProcessorTest {

  interface Greeter {
    String greet(String name);
  }

  @Timed
  static class GreeterImpl implements Greeter {
    @Override
    public String greet(String name) {
      return "hello " + name;
    }
  }

  static class PlainBean {}

  @Timed
  static class TimedWithoutInterface {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withBean(TimingBeanPostProcessor.class);

  @Test
  @DisplayName("a @Timed bean is replaced by a JDK proxy that still returns the real result")
  void wrapsTimedBeanInJdkProxy() {
    runner
        .withBean("greeter", GreeterImpl.class)
        .run(
            context -> {
              Greeter greeter = context.getBean(Greeter.class);
              assertThat(Proxy.isProxyClass(greeter.getClass())).isTrue();
              assertThat(greeter.greet("desk")).isEqualTo("hello desk");
            });
  }

  @Test
  @DisplayName("each call through the proxy logs the bean, the method and the elapsed time")
  void logsElapsedTimePerCall(CapturedOutput output) {
    runner
        .withBean("greeter", GreeterImpl.class)
        .run(context -> context.getBean(Greeter.class).greet("desk"));

    assertThat(output).containsPattern("greeter\\.greet took \\d+ ns");
  }

  @Test
  @DisplayName("a bean without @Timed is handed back untouched, the very same instance")
  void leavesOtherBeansAlone() {
    PlainBean plain = new PlainBean();
    runner
        .withBean(PlainBean.class, () -> plain)
        .run(context -> assertThat(context.getBean(PlainBean.class)).isSameAs(plain));
  }

  @Test
  @DisplayName("a @Timed bean with no interface fails startup, naming the bean")
  void rejectsTimedBeanWithoutInterface() {
    runner
        .withBean("timedWithoutInterface", TimedWithoutInterface.class)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("timedWithoutInterface");
            });
  }
}
