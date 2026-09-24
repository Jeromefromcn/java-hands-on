package com.jerome.slledger.bootstrap.demo.tx.proxy;

/** No interface, so a subclass (CGLIB) proxy is the only kind Spring can build. */
public class ConcretePositionQueries {

  public String describe() {
    return "concrete";
  }

  /** A subclass cannot override this, so its proxy cannot intercept it. */
  public final String describeFinal() {
    return "concrete, final";
  }
}
