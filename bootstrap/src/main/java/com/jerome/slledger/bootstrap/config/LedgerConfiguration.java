package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.adapter.persistence.PersistenceConfiguration;
import com.jerome.slledger.adapter.web.WebConfiguration;
import com.jerome.slledger.application.port.AccrualEntryQuery;
import com.jerome.slledger.application.port.CounterpartyDirectory;
import com.jerome.slledger.application.port.PositionDetailQuery;
import com.jerome.slledger.application.port.PositionListQuery;
import com.jerome.slledger.application.port.SecurityDirectory;
import com.jerome.slledger.application.port.TradeLedger;
import com.jerome.slledger.application.position.FindBookedTrade;
import com.jerome.slledger.application.position.IdempotentBooking;
import com.jerome.slledger.application.position.ListAccruals;
import com.jerome.slledger.application.position.ListPositions;
import com.jerome.slledger.application.position.OpenPosition;
import com.jerome.slledger.application.position.ReturnPosition;
import com.jerome.slledger.application.position.ReturnPositionOnce;
import com.jerome.slledger.application.position.ShowPosition;
import com.jerome.slledger.domain.calendar.HolidayCalendar;
import com.jerome.slledger.domain.calendar.HongKongHolidays;
import com.jerome.slledger.domain.loan.LoanPositionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The use cases, built here from plain constructors. {@code application} carries
 * {@code @Transactional} and no stereotype, so it depends on {@code spring-tx} and nothing from
 * Boot; the container that proxies it is this module's concern.
 */
@Configuration(proxyBeanMethods = false)
@Import({PersistenceConfiguration.class, WebConfiguration.class})
public class LedgerConfiguration {

  @Bean
  HolidayCalendar holidayCalendar() {
    return new HongKongHolidays();
  }

  @Bean
  OpenPosition openPosition(
      CounterpartyDirectory counterparties,
      SecurityDirectory securities,
      LoanPositionRepository positions,
      TradeLedger trades,
      HolidayCalendar calendar) {
    return new OpenPosition(counterparties, securities, positions, trades, calendar);
  }

  @Bean
  ReturnPositionOnce returnPositionOnce(
      LoanPositionRepository positions,
      SecurityDirectory securities,
      TradeLedger trades,
      HolidayCalendar calendar) {
    return new ReturnPositionOnce(positions, securities, trades, calendar);
  }

  @Bean
  ReturnPosition returnPosition(
      ReturnPositionOnce once, @Value("${ledger.return.max-attempts:3}") int maxAttempts) {
    return new ReturnPosition(once, maxAttempts);
  }

  @Bean
  FindBookedTrade findBookedTrade(TradeLedger trades) {
    return new FindBookedTrade(trades);
  }

  @Bean
  IdempotentBooking idempotentBooking(
      OpenPosition open, ReturnPosition returns, FindBookedTrade booked) {
    return new IdempotentBooking(open, returns, booked);
  }

  @Bean
  ListPositions listPositions(PositionListQuery query) {
    return new ListPositions(query);
  }

  @Bean
  ShowPosition showPosition(PositionDetailQuery query) {
    return new ShowPosition(query);
  }

  @Bean
  ListAccruals listAccruals(PositionDetailQuery positions, AccrualEntryQuery entries) {
    return new ListAccruals(positions, entries);
  }
}
