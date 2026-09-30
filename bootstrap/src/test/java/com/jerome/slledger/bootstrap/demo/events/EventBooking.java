package com.jerome.slledger.bootstrap.demo.events;

import com.jerome.slledger.application.event.DomainEvents;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemoRows;
import org.springframework.transaction.annotation.Transactional;

/** Writes a position and publishes an in-process event, all inside one transaction. */
public class EventBooking {

  private final TxDemoRows rows;
  private final DomainEvents events;

  public EventBooking(TxDemoRows rows, DomainEvents events) {
    this.rows = rows;
    this.events = events;
  }

  @Transactional
  public void book(String positionId, boolean failAfterPublishing) {
    rows.insertPosition(positionId, 100);
    events.publish(new PositionBooked(positionId));
    if (failAfterPublishing) {
      throw new IllegalStateException("booking failed after the event was published");
    }
  }
}
