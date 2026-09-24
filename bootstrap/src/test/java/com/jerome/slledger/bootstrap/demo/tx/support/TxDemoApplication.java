package com.jerome.slledger.bootstrap.demo.tx.support;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Auto-configuration on, component scan off: each test names exactly the beans it loads. Stage-5
 * demos start from here, never from {@code SlLedgerApplication}, which has no datasource.
 *
 * <p>Deliberately not {@code @SpringBootConfiguration}, which is a {@code @Configuration}: the real
 * application component-scans this package on the test classpath (see {@code ApplicationScanTest}).
 * As a source passed to {@code SpringApplicationBuilder} it is processed anyway, because
 * {@code @EnableAutoConfiguration} carries an {@code @Import}.
 */
@EnableAutoConfiguration
public class TxDemoApplication {}
