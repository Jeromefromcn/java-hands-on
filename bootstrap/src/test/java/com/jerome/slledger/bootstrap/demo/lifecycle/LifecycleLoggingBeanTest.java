package com.jerome.slledger.bootstrap.demo.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;

class LifecycleLoggingBeanTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(CallOrderRecorder.class)
          .withBean(RecordingBeanFactoryPostProcessor.class)
          .withBean(RecordingBeanPostProcessor.class)
          .withBean(AllBeansReadyMarker.class)
          .withBean(ContainerPhaseBean.class)
          // registerBean, not withBean: withBean only takes a customizer alongside a
          // Supplier, which would bypass constructor injection.
          .withInitializer(
              context ->
                  ((GenericApplicationContext) context)
                      .registerBean(
                          LifecycleLoggingBean.class,
                          definition -> {
                            definition.setInitMethodName("initMethod");
                            definition.setDestroyMethodName("destroyMethod");
                          }));

  @Test
  @DisplayName(
      "the bean is constructed, injected, told its name, then initialised by @PostConstruct,"
          + " afterPropertiesSet and the init method, in that order")
  void beanCreationCallbacks() {
    assertThat(
            slice(
                "constructor",
                "setter injection",
                "setBeanName",
                "@PostConstruct",
                "afterPropertiesSet",
                "initMethod"))
        .containsExactly(
            "constructor",
            "setter injection",
            "setBeanName",
            "@PostConstruct",
            "afterPropertiesSet",
            "initMethod");
  }

  // Expected @PostConstruct first, since CommonAnnotationBeanPostProcessor is PriorityOrdered.
  // Measured the opposite: it is also a MergedBeanDefinitionPostProcessor, and
  // PostProcessorRegistrationDelegate re-registers those last, moving it behind every plain BPP.
  // See docs/decisions/04-ioc-container.md.
  @Test
  @DisplayName(
      "the factory post-processor runs before any bean exists, and a plain bean post-processor's"
          + " before-initialization hook runs before @PostConstruct, despite PriorityOrdered")
  void postProcessorHooksAroundCreation() {
    assertThat(
            slice(
                "postProcessBeanFactory",
                "bpp.beforeInstantiation",
                "constructor",
                "bpp.afterInstantiation",
                "setter injection",
                "setBeanName",
                "bpp.beforeInitialization",
                "@PostConstruct",
                "afterPropertiesSet",
                "initMethod",
                "bpp.afterInitialization"))
        .containsExactly(
            "postProcessBeanFactory",
            "bpp.beforeInstantiation",
            "constructor",
            "bpp.afterInstantiation",
            "setter injection",
            "setBeanName",
            "bpp.beforeInitialization",
            "@PostConstruct",
            "afterPropertiesSet",
            "initMethod",
            "bpp.afterInitialization");
  }

  @Test
  @DisplayName(
      "once every singleton is initialised the container fires afterSingletonsInstantiated,"
          + " then starts SmartLifecycle beans, then publishes ContextRefreshedEvent")
  void containerLevelPhases() {
    assertThat(
            slice(
                "bpp.afterInitialization",
                "afterSingletonsInstantiated",
                "SmartLifecycle.start",
                "ContextRefreshedEvent"))
        .containsExactly(
            "bpp.afterInitialization",
            "afterSingletonsInstantiated",
            "SmartLifecycle.start",
            "ContextRefreshedEvent");
  }

  @Test
  @DisplayName(
      "closing the context stops SmartLifecycle beans first, then runs @PreDestroy, destroy"
          + " and the destroy method")
  void destructionCallbacks() {
    assertThat(slice("SmartLifecycle.stop", "@PreDestroy", "destroy", "destroyMethod"))
        .containsExactly("SmartLifecycle.stop", "@PreDestroy", "destroy", "destroyMethod");
  }

  /** Runs a context to completion, closed included, and keeps only the named calls. */
  private List<String> slice(String... names) {
    AtomicReference<CallOrderRecorder> recorder = new AtomicReference<>();
    runner.run(context -> recorder.set(context.getBean(CallOrderRecorder.class)));
    Set<String> wanted = Set.of(names);
    return recorder.get().calls().stream().filter(wanted::contains).toList();
  }
}
