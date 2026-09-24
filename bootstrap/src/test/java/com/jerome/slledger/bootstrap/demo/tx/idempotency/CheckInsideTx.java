package com.jerome.slledger.bootstrap.demo.tx.idempotency;

import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Idempotency check inside the transaction: the key insert commits or rolls back with the business.
 * Lower order runs outside.
 *
 * <p>Deliberately not {@code @Configuration}: the real application component-scans this package on
 * the test classpath (see {@code ApplicationScanTest}). Passed to {@code TxDemo.start} explicitly,
 * it is still processed as configuration, because {@code @EnableTransactionManagement} carries an
 * {@code @Import}.
 */
@EnableTransactionManagement(order = 0)
public class CheckInsideTx {

  @Bean
  IdempotencyAspect idempotencyAspect(JdbcClient jdbc) {
    return new IdempotencyAspect(jdbc, 10);
  }
}
