package com.jerome.slledger.bootstrap.demo.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.persistence.testing.SharedPostgres;
import com.jerome.slledger.application.event.DomainEvents;
import com.jerome.slledger.bootstrap.config.SpringDomainEvents;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemo;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stage 10, exercise 1: what an in-process event does and does not promise. The listeners are
 * plain, after-commit, and after-commit that writes; the publisher is a {@code @Transactional}
 * booking that can fail after publishing. See docs/decisions/10-messaging.md.
 */
@Timeout(60)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InProcessEventsIT {

  private static final StringBuilder EVIDENCE = new StringBuilder();

  private ConfigurableApplicationContext context;
  private EventBooking booking;
  private EventListeners listeners;
  private TxDemoRows rows;

  @Configuration(proxyBeanMethods = false)
  static class Beans {
    @Bean
    DomainEvents domainEvents(ApplicationEventPublisher publisher) {
      return new SpringDomainEvents(publisher);
    }

    @Bean
    EventBooking eventBooking(TxDemoRows rows, DomainEvents events) {
      return new EventBooking(rows, events);
    }

    @Bean
    NewTransactionWriter newTransactionWriter(TxDemoRows rows) {
      return new NewTransactionWriter(rows);
    }

    @Bean(destroyMethod = "close")
    EventListeners eventListeners(TxDemoRows rows, NewTransactionWriter newTransaction) {
      return new EventListeners(rows, newTransaction);
    }
  }

  @BeforeAll
  static void startPostgres() {
    SharedPostgres.start();
  }

  @AfterAll
  static void writeEvidence() {
    Evidence.write("10-after-commit.txt", EVIDENCE.toString());
  }

  private void start() {
    context = TxDemo.start(Beans.class);
    booking = context.getBean(EventBooking.class);
    listeners = context.getBean(EventListeners.class);
    rows = context.getBean(TxDemoRows.class);
  }

  private void stop() {
    context.close();
  }

  private static boolean positionExists(TxDemoRows rows, String id) {
    try {
      rows.outstanding(id);
      return true;
    } catch (org.springframework.dao.EmptyResultDataAccessException absent) {
      return false;
    }
  }

  @Test
  @Order(1)
  @DisplayName("a plain listener is told about a booking that then rolls back")
  void reproducesPhantomEvent() {
    // See docs/decisions/10-messaging.md
    start();
    try {
      assertThatThrownBy(() -> booking.book("P1", true)).isInstanceOf(IllegalStateException.class);

      assertThat(listeners.plainSeen).containsExactly("P1");
      assertThat(positionExists(rows, "P1")).isFalse();
      // Called inside the transaction, so a reader on another connection cannot see the row it
      // was told about even in the case where the booking goes on to commit.
      assertThat(listeners.plainCouldReadRow).containsExactly(false);
      EVIDENCE
          .append("plain @EventListener, booking rolled back: listener told=")
          .append(listeners.plainSeen)
          .append(" row exists=")
          .append(positionExists(rows, "P1"))
          .append('\n');
    } finally {
      stop();
    }
  }

  @Test
  @Order(2)
  @DisplayName("an AFTER_COMMIT listener is not called on rollback, and sees the row on commit")
  void afterCommitWaitsForTheCommit() {
    start();
    try {
      assertThatThrownBy(() -> booking.book("P2", true)).isInstanceOf(IllegalStateException.class);
      assertThat(listeners.afterCommitSeen).isEmpty();

      booking.book("P3", false);

      assertThat(listeners.afterCommitSeen).containsExactly("P3");
      assertThat(listeners.afterCommitCouldReadRow).containsExactly(true);
      // Same booking, plain listener: told, but could not read the row.
      assertThat(listeners.plainCouldReadRow).containsExactly(false, false);
      EVIDENCE
          .append("AFTER_COMMIT, rolled back: called=")
          .append(0)
          .append("; committed: called=1, another connection could read the row=")
          .append(listeners.afterCommitCouldReadRow.get(0))
          .append("; the plain listener on the same commit could read it=")
          .append(listeners.plainCouldReadRow.get(1))
          .append('\n');
    } finally {
      stop();
    }
  }

  @Test
  @Order(3)
  @DisplayName(
      "an AFTER_COMMIT listener writes on the finished transaction unless it opens its own")
  void afterCommitListenerHasNoTransactionOfItsOwn() {
    start();
    try {
      listeners.afterCommitWrites = true;
      booking.book("P4", false);
      long rowsAfterPlainWrite = rows.tradeCount("P4");
      String plainName = listeners.writerTransactionNames.get(0);

      listeners.afterCommitWrites = false;
      listeners.afterCommitWritesInNewTransaction = true;
      booking.book("P5", false);
      long rowsAfterNewTransaction = rows.tradeCount("P5");
      String newName = listeners.writerTransactionNames.get(1);

      // The plain write ran under the booking's transaction name: the transaction had committed,
      // and the write belongs to nothing. That it reached the table is the JDBC driver's doing:
      // pgjdbc commits an open transaction when the pool resets autocommit at cleanup. A JPA
      // write in the same place would never be flushed. REQUIRES_NEW is the only form that says
      // what it means.
      assertThat(plainName).endsWith("EventBooking.book");
      assertThat(newName).endsWith("NewTransactionWriter.write");
      assertThat(rowsAfterPlainWrite).isEqualTo(1);
      assertThat(rowsAfterNewTransaction).isEqualTo(1);
      EVIDENCE
          .append("write from an AFTER_COMMIT listener, no new transaction: ran under '")
          .append(plainName)
          .append("' (already committed), rows visible=")
          .append(rowsAfterPlainWrite)
          .append(" (by the driver's autocommit reset); with REQUIRES_NEW: ran under '")
          .append(newName)
          .append("', rows visible=")
          .append(rowsAfterNewTransaction)
          .append('\n');
    } finally {
      stop();
    }
  }

  @Test
  @Order(4)
  @DisplayName("a listener that fails after the commit loses the event and records nothing")
  void reproducesAfterCommitEventLost() {
    // See docs/decisions/10-messaging.md
    start();
    try {
      listeners.afterCommitThrows = true;
      Throwable toCaller = null;
      try {
        booking.book("P6", false);
      } catch (RuntimeException e) {
        toCaller = e;
      }

      // The booking is committed whatever the caller was told.
      assertThat(positionExists(rows, "P6")).isTrue();
      assertThat(listeners.afterCommitSeen).containsExactly("P6");
      // And nothing in the database says an event was owed and not delivered: retrying is
      // impossible because there is nothing to retry from. That is what the outbox row is for.
      assertThat(rows.tradeCount("P6")).isZero();
      EVIDENCE
          .append("AFTER_COMMIT listener threw: booking committed=true, caller received=")
          .append(toCaller == null ? "no exception" : toCaller.getClass().getSimpleName())
          .append(", rows recording the undelivered event=0\n");
    } finally {
      stop();
    }
  }
}
