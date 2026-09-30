package com.jerome.slledger.bootstrap.demo.events;

import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The listeners the stage-10 in-process demos compare. Each records what it was told and what it
 * could see; a read is made from another connection, which is what a reader outside the publisher's
 * transaction would have.
 */
public class EventListeners {

  private final TxDemoRows rows;
  private final NewTransactionWriter newTransaction;
  private final ExecutorService otherConnection = Executors.newSingleThreadExecutor();

  public final List<String> plainSeen = new CopyOnWriteArrayList<>();
  public final List<Boolean> plainCouldReadRow = new CopyOnWriteArrayList<>();
  public final List<String> afterCommitSeen = new CopyOnWriteArrayList<>();
  public final List<Boolean> afterCommitCouldReadRow = new CopyOnWriteArrayList<>();
  public final List<String> writerTransactionNames = new CopyOnWriteArrayList<>();
  public volatile boolean afterCommitThrows;
  public volatile boolean afterCommitWrites;
  public volatile boolean afterCommitWritesInNewTransaction;

  public EventListeners(TxDemoRows rows, NewTransactionWriter newTransaction) {
    this.rows = rows;
    this.newTransaction = newTransaction;
  }

  @EventListener
  void plain(PositionBooked event) throws Exception {
    plainSeen.add(event.positionId());
    plainCouldReadRow.add(readFromAnotherConnection(event.positionId()));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void afterCommit(PositionBooked event) throws Exception {
    afterCommitSeen.add(event.positionId());
    afterCommitCouldReadRow.add(readFromAnotherConnection(event.positionId()));
    if (afterCommitWrites) {
      // Runs after the commit, on the same thread, with the finished transaction still bound: the
      // name it reports is the booking's, not a new transaction's.
      writerTransactionNames.add(
          String.valueOf(TransactionSynchronizationManager.getCurrentTransactionName()));
      rows.insertTrade(event.positionId(), 1);
    }
    if (afterCommitWritesInNewTransaction) {
      newTransaction.write(event.positionId(), writerTransactionNames);
    }
    if (afterCommitThrows) {
      throw new IllegalStateException("the notification failed after the commit");
    }
  }

  private boolean readFromAnotherConnection(String positionId) throws Exception {
    return otherConnection
        .submit(() -> rows.tradeCount(positionId) >= 0 && rowVisible(positionId))
        .get();
  }

  private boolean rowVisible(String positionId) {
    try {
      rows.outstanding(positionId);
      return true;
    } catch (org.springframework.dao.EmptyResultDataAccessException absent) {
      return false;
    }
  }

  public void close() {
    otherConnection.shutdownNow();
  }
}
