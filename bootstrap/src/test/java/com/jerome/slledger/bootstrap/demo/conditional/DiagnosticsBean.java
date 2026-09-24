package com.jerome.slledger.bootstrap.demo.conditional;

import org.springframework.context.annotation.Conditional;

/**
 * Exists only when {@link OnOperatingSystemCondition} matches; its presence is the behaviour.
 *
 * <p>Deliberately not a {@code @Component}: the real application component-scans this package on
 * the test classpath (see {@code ApplicationScanTest}). Registered explicitly, the condition is
 * still evaluated, and the default bean name is still {@code diagnosticsBean}.
 */
@Conditional(OnOperatingSystemCondition.class)
public class DiagnosticsBean {}
