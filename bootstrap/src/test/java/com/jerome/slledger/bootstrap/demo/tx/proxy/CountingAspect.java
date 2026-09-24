package com.jerome.slledger.bootstrap.demo.tx.proxy;

import java.util.concurrent.atomic.AtomicInteger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/** Counts every intercepted call on the demo beans in this package. */
@Aspect
public class CountingAspect {

  private final AtomicInteger count = new AtomicInteger();

  @Around(
      "within(com.jerome.slledger.bootstrap.demo.tx.proxy..*)"
          + " && !within(com.jerome.slledger.bootstrap.demo.tx.proxy.CountingAspect)")
  public Object count(ProceedingJoinPoint call) throws Throwable {
    count.incrementAndGet();
    return call.proceed();
  }

  public int count() {
    return count.get();
  }
}
