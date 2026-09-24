package com.jerome.slledger.bootstrap.demo.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.bootstrap.demo.tx.selfinvocation.ProxyLookupReturnService;
import com.jerome.slledger.bootstrap.demo.tx.selfinvocation.ReturnRecorder;
import com.jerome.slledger.bootstrap.demo.tx.selfinvocation.SelfInjectedReturnService;
import com.jerome.slledger.bootstrap.demo.tx.selfinvocation.SelfInvokingReturnService;
import com.jerome.slledger.bootstrap.demo.tx.selfinvocation.SplitReturnService;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemo;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * A return of 30 against position P1 (outstanding 100): decrement the position, then fail before
 * the trade is booked. Whether the decrement survives depends only on whether the call into the
 * {@code @Transactional} method went through the proxy.
 */
class SelfInvocationIT {

  private static final String FAILURE = "trade booking failed";

  // The call this.recordReturn() goes straight to the target object, never to the proxy that
  // holds the transaction advice, so the decrement runs in autocommit and stays committed.
  // See docs/decisions/05-aop-transactions.md
  @Test
  @DisplayName("self-invocation skips the proxy: the decrement commits and the trade is missing")
  void reproducesSelfInvocationPartialWrite() {
    try (var context = TxDemo.start(SelfInvokingReturnService.class)) {
      TxDemoRows rows = context.getBean(TxDemoRows.class);
      SelfInvokingReturnService service = context.getBean(SelfInvokingReturnService.class);
      rows.insertPosition("P1", 100);
      String before = snapshot(rows);

      assertThatThrownBy(service::returnAll)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(FAILURE);

      assertThat(service.transactionActiveInRecordReturn()).isFalse();
      assertThat(rows.outstanding("P1")).isEqualTo(70);
      assertThat(rows.tradeCount("P1")).isZero();

      Evidence.write(
          "05-self-invocation.txt",
          "SelfInvocationIT.reproducesSelfInvocationPartialWrite\n"
              + "returnAll() -> this.recordReturn() [@Transactional], fails before the trade"
              + " insert\n\n"
              + "before: "
              + before
              + "\nafter:  "
              + snapshot(rows)
              + "\ntransaction active inside recordReturn(): "
              + service.transactionActiveInRecordReturn()
              + "\n");
    }
  }

  @Test
  @DisplayName("fix 1: the transactional method moved to a second bean rolls back as one unit")
  void splitIntoSecondBeanRollsBack() {
    try (var context = TxDemo.start(SplitReturnService.class, ReturnRecorder.class)) {
      SplitReturnService service = context.getBean(SplitReturnService.class);

      assertRolledBack(context.getBean(TxDemoRows.class), service::returnAll);
      assertThat(context.getBean(ReturnRecorder.class).transactionActiveInRecordReturn()).isTrue();
    }
  }

  // Not @Configuration, so no component scan can pick it up; passed to TxDemo.start explicitly.
  @EnableAspectJAutoProxy(exposeProxy = true)
  static class ExposeProxy {}

  @Test
  @DisplayName("fix 2: calling through AopContext.currentProxy() rolls back as one unit")
  void currentProxyRollsBack() {
    try (var context = TxDemo.start(ProxyLookupReturnService.class, ExposeProxy.class)) {
      ProxyLookupReturnService service = context.getBean(ProxyLookupReturnService.class);

      assertRolledBack(context.getBean(TxDemoRows.class), service::returnAll);
      assertThat(service.transactionActiveInRecordReturn()).isTrue();
    }
  }

  @Test
  @DisplayName("fix 3: calling through a @Lazy self-reference rolls back as one unit")
  void lazySelfInjectionRollsBack() {
    try (var context = TxDemo.start(SelfInjectedReturnService.class)) {
      SelfInjectedReturnService service = context.getBean(SelfInjectedReturnService.class);

      assertRolledBack(context.getBean(TxDemoRows.class), service::returnAll);
      assertThat(service.transactionActiveInRecordReturn()).isTrue();
    }
  }

  private static void assertRolledBack(TxDemoRows rows, Runnable returnAll) {
    rows.insertPosition("P1", 100);

    assertThatThrownBy(returnAll::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(FAILURE);

    assertThat(rows.outstanding("P1")).isEqualTo(100);
    assertThat(rows.tradeCount("P1")).isZero();
  }

  private static String snapshot(TxDemoRows rows) {
    return "loan_position P1 outstanding="
        + rows.outstanding("P1")
        + ", loan_trade rows for P1="
        + rows.tradeCount("P1");
  }
}
