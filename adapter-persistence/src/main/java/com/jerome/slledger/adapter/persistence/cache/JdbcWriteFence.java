package com.jerome.slledger.adapter.persistence.cache;

import com.jerome.slledger.application.cache.StaleFencingTokenException;
import com.jerome.slledger.application.cache.WriteFence;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The fence, in the database the protected write lands in.
 *
 * <p>One conditional upsert: insert the token, or, if the resource already has one, raise it only
 * if the stored token is not higher. No row returned means the stored token was higher and the
 * caller's is stale. Equal tokens are admitted, because one holder makes several writes under one
 * token.
 *
 * <p>Refuses to run outside a transaction, as {@code Propagation.MANDATORY} would ({@code
 * \@Transactional} belongs to {@code application}, and an architecture rule keeps it there): a
 * fence outside the writer's transaction would admit a token and then let the write fail, or refuse
 * nothing at all. The row lock the upsert takes is held until that transaction ends, so two writers
 * of one resource cannot interleave their checks.
 */
@Repository
public class JdbcWriteFence implements WriteFence {

  private static final String ADMIT =
      """
      INSERT INTO write_fence (resource, token) VALUES (?, ?)
      ON CONFLICT (resource) DO UPDATE SET token = EXCLUDED.token
        WHERE write_fence.token <= EXCLUDED.token
      RETURNING token
      """;

  private final JdbcClient jdbc;

  JdbcWriteFence(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void admit(String resource, long token) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "The write fence must be admitted inside the transaction of the write it guards");
    }
    boolean admitted =
        jdbc.sql(ADMIT).param(resource).param(token).query(Long.class).optional().isPresent();
    if (!admitted) {
      throw new StaleFencingTokenException(resource, token);
    }
  }
}
