package com.jerome.slledger.adapter.persistence.cache;

import com.jerome.slledger.application.cache.FencingTokens;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Tokens from a Postgres sequence: the store that checks a token is the store that issues it. */
@Repository
public class JdbcFencingTokens implements FencingTokens {

  private final JdbcClient jdbc;

  JdbcFencingTokens(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public long next() {
    return jdbc.sql("SELECT nextval('fencing_token_seq')").query(Long.class).single();
  }
}
