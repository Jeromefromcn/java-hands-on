package com.jerome.slledger.application.cache;

/**
 * What a cache holds for a key: a value, or the fact that there is none. Nothing stores or returns
 * {@code null}, so "the tier has no entry" ({@code Optional.empty()}) and "the source has no such
 * key" ({@link Absent}) cannot be confused.
 */
public sealed interface Entry<V> {

  record Present<V>(V value) implements Entry<V> {}

  record Absent<V>() implements Entry<V> {}

  static <V> Entry<V> present(V value) {
    return new Present<>(value);
  }

  static <V> Entry<V> absent() {
    return new Absent<>();
  }
}
