package com.jerome.slledger.application.saga;

/**
 * A place where the process could die. The saga calls {@link #reached} between logging a step and
 * running it, and between running it and logging its outcome; a test's implementation throws to
 * play the crash. Production passes {@link #NONE}.
 */
public interface FailurePoint {

  FailurePoint NONE = point -> {};

  void reached(String point);
}
