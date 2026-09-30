package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.application.event.DomainEvents;
import org.springframework.context.ApplicationEventPublisher;

/** {@link DomainEvents} over Spring's application events. */
public class SpringDomainEvents implements DomainEvents {

  private final ApplicationEventPublisher publisher;

  public SpringDomainEvents(ApplicationEventPublisher publisher) {
    this.publisher = publisher;
  }

  @Override
  public void publish(Object event) {
    publisher.publishEvent(event);
  }
}
