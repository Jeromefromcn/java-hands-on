package com.jerome.slledger.bootstrap.demo.scope;

import org.springframework.context.annotation.Scope;
import org.springframework.web.context.WebApplicationContext;

/**
 * The counterparty the current request acts for. Plain request scope, no proxy — unlike
 * {@code @RequestScope}, which adds one — so that injecting it into a singleton breaks.
 */
@Scope(WebApplicationContext.SCOPE_REQUEST)
class CounterpartyRequestContext {

  private String counterpartyId;

  public String getCounterpartyId() {
    return counterpartyId;
  }

  public void setCounterpartyId(String counterpartyId) {
    this.counterpartyId = counterpartyId;
  }
}
