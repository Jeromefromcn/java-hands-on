package com.jerome.slledger.application.security;

/**
 * The caller of the work running on this thread. Ambient, like the trace: the actor of a booking is
 * metadata for the audit row, and threading it through every command would change what stages 6-11
 * wrote for no gain. Reads do not use it; they take a {@link Visibility} argument, so that scoping
 * is visible in the signature.
 */
public interface Callers {

  /** No web request: every caller is {@link Caller#SYSTEM}. */
  Callers SYSTEM_ONLY = () -> Caller.SYSTEM;

  Caller current();
}
