package com.jerome.slledger.bootstrap.demo.scope;

import org.springframework.web.context.annotation.RequestScope;

/** Same as {@link CounterpartyRequestContext}, but {@code @RequestScope} adds a CGLIB proxy. */
@RequestScope
class ProxiedCounterpartyRequestContext {

  private String counterpartyId;

  public String getCounterpartyId() {
    return counterpartyId;
  }

  public void setCounterpartyId(String counterpartyId) {
    this.counterpartyId = counterpartyId;
  }
}
