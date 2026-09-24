package com.jerome.slledger.bootstrap.demo.tx.async;

import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Turns on {@code @Async}; Boot's auto-configured task executor runs the methods.
 *
 * <p>Deliberately not {@code @Configuration}: the real application component-scans this package on
 * the test classpath (see {@code ApplicationScanTest}). Passed to {@code TxDemo.start} explicitly,
 * it is still processed as configuration, because {@code @EnableAsync} carries an {@code @Import}.
 */
@EnableAsync
public class AsyncConfig {}
