package com.jerome.slledger.application.port;

/**
 * The paged position listing. A port of its own, not a method on the domain repository: a listing
 * is a read model the use case asks for, not something the aggregate's language has a word for.
 */
public interface PositionListQuery {

  /** Page {@code page} (zero-based) of {@code size} rows, ordered by position id. */
  PositionPage page(int page, int size);
}
