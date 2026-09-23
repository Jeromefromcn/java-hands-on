package com.jerome.slledger.bootstrap.demo.scope;

class DirectlyInjectedHolder {

  private final CounterpartyRequestContext context;

  DirectlyInjectedHolder(CounterpartyRequestContext context) {
    this.context = context;
  }

  String currentId() {
    return context.getCounterpartyId();
  }
}
