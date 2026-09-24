package com.jerome.slledger.bootstrap.demo.tx.proxy;

/** Implements an interface, so Spring has a choice: a JDK proxy of the interface, or a subclass. */
public class InterfacePositionQueries implements PositionQueries {

  @Override
  public String describe() {
    return "interface-backed";
  }
}
