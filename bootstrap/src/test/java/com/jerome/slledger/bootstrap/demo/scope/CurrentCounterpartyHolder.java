package com.jerome.slledger.bootstrap.demo.scope;

import org.springframework.beans.factory.ObjectProvider;

class CurrentCounterpartyHolder {

  private final ObjectProvider<CounterpartyRequestContext> context;

  CurrentCounterpartyHolder(ObjectProvider<CounterpartyRequestContext> context) {
    this.context = context;
  }

  String currentId() {
    return context.getObject().getCounterpartyId();
  }
}
