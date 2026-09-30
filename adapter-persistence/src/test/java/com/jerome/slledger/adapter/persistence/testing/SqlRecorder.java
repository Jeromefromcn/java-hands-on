package com.jerome.slledger.adapter.persistence.testing;

import java.util.ArrayList;
import java.util.List;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records every SQL string Hibernate prepares, in order. Registered through {@link #PROPERTY}; one
 * shared list per JVM, so a test clears it before the part it measures.
 *
 * <p>Counting stays with Hibernate {@code Statistics}; this is for the SQL text, where the text is
 * the evidence ({@code limit} present or not, {@code for update} or {@code for no key update}).
 */
public class SqlRecorder implements StatementInspector {

  // StatementInspector extends Serializable; nothing here is ever serialised.
  @java.io.Serial private static final long serialVersionUID = 1L;

  public static final String PROPERTY =
      "spring.jpa.properties.hibernate.session_factory.statement_inspector="
          + SqlRecorder.class.getName();

  private static final List<String> STATEMENTS = new ArrayList<>();
  private static final List<Boolean> IN_TRANSACTION = new ArrayList<>();

  @Override
  public String inspect(String sql) {
    synchronized (STATEMENTS) {
      STATEMENTS.add(sql);
      // Hibernate prepares on the caller's thread, so this is the caller's transaction state.
      IN_TRANSACTION.add(TransactionSynchronizationManager.isActualTransactionActive());
    }
    return sql;
  }

  public static void clear() {
    synchronized (STATEMENTS) {
      STATEMENTS.clear();
      IN_TRANSACTION.clear();
    }
  }

  /** For each statement in {@link #statements()}, whether a Spring transaction was active. */
  public static List<Boolean> inTransaction() {
    synchronized (STATEMENTS) {
      return List.copyOf(IN_TRANSACTION);
    }
  }

  public static List<String> statements() {
    synchronized (STATEMENTS) {
      return List.copyOf(STATEMENTS);
    }
  }
}
