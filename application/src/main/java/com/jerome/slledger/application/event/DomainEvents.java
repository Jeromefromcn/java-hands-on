package com.jerome.slledger.application.event;

/**
 * Tells the rest of this process that something happened. In-process only: a listener registered
 * for the commit of the publisher's transaction runs after it, and is lost if the process dies
 * between the commit and the call. Anything another system must eventually hear about goes through
 * the outbox instead.
 */
public interface DomainEvents {

  void publish(Object event);
}
