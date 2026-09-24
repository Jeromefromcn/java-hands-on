package com.jerome.slledger.bootstrap.demo.conditional;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when the JVM runs on Linux. No business meaning: it exists to show a {@link Condition}
 * written by hand rather than consumed through {@code @ConditionalOnProperty}, and {@code os.name}
 * is something a test can observe without mocking anything.
 *
 * <p>Read through the {@code Environment}, not {@code System.getProperty} directly, so it resolves
 * the same way every other property does — and a test can override it.
 */
public class OnOperatingSystemCondition implements Condition {

  private static final String FAMILY = "Linux";

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    String osName = context.getEnvironment().getProperty("os.name", "");
    return osName.startsWith(FAMILY);
  }
}
