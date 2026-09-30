package com.jerome.slledger.application.cache;

/**
 * Tells the other instances that a key changed. Fire and forget: a message sent while an instance
 * is not listening is lost, so nothing may depend on it arriving. A per-tier TTL is what bounds how
 * long a lost message can matter.
 */
public interface Invalidation<K> {

  void publish(K key);
}
