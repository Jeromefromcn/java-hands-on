package com.jerome.slledger.bootstrap.demo.tx.idempotency;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Claims the request key by inserting it; the primary key makes the claim atomic. Whether the
 * insert belongs to the business transaction depends only on this advice's order relative to the
 * transaction advice, which is why the order is a constructor argument rather than an annotation.
 */
@Aspect
public class IdempotencyAspect implements Ordered {

  private final JdbcClient jdbc;
  private final int order;
  private volatile boolean transactionActiveAtCheck;

  public IdempotencyAspect(JdbcClient jdbc, int order) {
    this.jdbc = jdbc;
    this.order = order;
  }

  @Around("@annotation(com.jerome.slledger.bootstrap.demo.tx.idempotency.Idempotent)")
  public Object claimKey(ProceedingJoinPoint call) throws Throwable {
    String key = firstStringArgument(call);
    transactionActiveAtCheck = TransactionSynchronizationManager.isActualTransactionActive();
    int claimed =
        jdbc.sql("INSERT INTO idempotency_key(key) VALUES (?) ON CONFLICT DO NOTHING")
            .param(key)
            .update();
    if (claimed == 0) {
      throw new DuplicateRequestException(key);
    }
    return call.proceed();
  }

  private static String firstStringArgument(ProceedingJoinPoint call) {
    for (Object argument : call.getArgs()) {
      if (argument instanceof String key) {
        return key;
      }
    }
    throw new IllegalStateException("@Idempotent method has no String argument to use as key");
  }

  @Override
  public int getOrder() {
    return order;
  }

  public boolean transactionActiveAtCheck() {
    return transactionActiveAtCheck;
  }
}
