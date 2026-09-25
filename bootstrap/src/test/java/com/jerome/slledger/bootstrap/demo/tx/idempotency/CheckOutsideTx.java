package com.jerome.slledger.bootstrap.demo.tx.idempotency;

import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Idempotency check outside the transaction: the key insert commits on its own, first. Lower order
 * runs outside.
 *
 * <p>Deliberately not {@code @Configuration}: the real application component-scans this package on
 * the test classpath (see {@code ApplicationScanIT}). Passed to {@code TxDemo.start} explicitly, it
 * is still processed as configuration, because {@code @EnableTransactionManagement} carries an
 * {@code @Import}.
 */
@EnableTransactionManagement(order = 10)
public class CheckOutsideTx {

  @Bean
  IdempotencyAspect idempotencyAspect(JdbcClient jdbc) {
    return new IdempotencyAspect(jdbc, 0);
  }
}
