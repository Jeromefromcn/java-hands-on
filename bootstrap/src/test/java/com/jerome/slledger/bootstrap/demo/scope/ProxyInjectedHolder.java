package com.jerome.slledger.bootstrap.demo.scope;

class ProxyInjectedHolder {

  private final ProxiedCounterpartyRequestContext context;

  ProxyInjectedHolder(ProxiedCounterpartyRequestContext context) {
    this.context = context;
  }

  ProxiedCounterpartyRequestContext injected() {
    return context;
  }

  String currentId() {
    return context.getCounterpartyId();
  }
}
