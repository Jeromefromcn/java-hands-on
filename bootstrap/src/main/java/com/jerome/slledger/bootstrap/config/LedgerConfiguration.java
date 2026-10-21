package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.adapter.persistence.PersistenceConfiguration;
import com.jerome.slledger.adapter.web.WebConfiguration;
import com.jerome.slledger.application.audit.Audit;
import com.jerome.slledger.application.audit.AuditQuery;
import com.jerome.slledger.application.audit.AuditStore;
import com.jerome.slledger.application.audit.ListAudit;
import com.jerome.slledger.application.collateral.CollateralHolds;
import com.jerome.slledger.application.event.DomainEvents;
import com.jerome.slledger.application.messaging.Outbox;
import com.jerome.slledger.application.observability.LedgerMetrics;
import com.jerome.slledger.application.port.AccrualEntryQuery;
import com.jerome.slledger.application.port.CounterpartyDirectory;
import com.jerome.slledger.application.port.MarketPriceSource;
import com.jerome.slledger.application.port.PositionCursorQuery;
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
import com.jerome.slledger.application.quote.LastKnownPrices;
import com.jerome.slledger.application.quote.PriceFanOut;
import com.jerome.slledger.application.quote.QuotePrice;
import com.jerome.slledger.application.rollout.ManageRollout;
import com.jerome.slledger.application.rollout.RolloutStore;
import com.jerome.slledger.application.saga.CreditReservations;
import com.jerome.slledger.application.saga.FailurePoint;
import com.jerome.slledger.application.saga.OpenPositionSaga;
import com.jerome.slledger.application.saga.OpenPositionSteps;
import com.jerome.slledger.application.saga.SagaLog;
import com.jerome.slledger.application.saga.SagaRecovery;
import com.jerome.slledger.application.saga.TransactionalOpenPositionSteps;
import com.jerome.slledger.application.security.Callers;
import com.jerome.slledger.domain.calendar.HolidayCalendar;
import com.jerome.slledger.domain.calendar.HongKongHolidays;
import com.jerome.slledger.domain.loan.LoanPositionRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The use cases, built here from plain constructors. {@code application} carries
 * {@code @Transactional} and no stereotype, so it depends on {@code spring-tx} and nothing from
 * Boot; the container that proxies it is this module's concern.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RolloutProperties.class)
@Import({
  PersistenceConfiguration.class,
  WebConfiguration.class,
  MarketDataExecutorConfiguration.class,
  CacheConfiguration.class,
  MessagingConfiguration.class,
  BatchConfiguration.class,
  RevaluationConfiguration.class
})
public class LedgerConfiguration {

  @Bean
  HolidayCalendar holidayCalendar() {
    return new HongKongHolidays();
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  DomainEvents domainEvents(ApplicationEventPublisher publisher) {
    return new SpringDomainEvents(publisher);
  }

  @Bean
  OpenPosition openPosition(
      CounterpartyDirectory counterparties,
      SecurityDirectory securities,
      LoanPositionRepository positions,
      TradeLedger trades,
      HolidayCalendar calendar,
      ObjectProvider<Outbox> outbox,
      Clock clock,
      ObjectProvider<LedgerMetrics> metrics,
      Audit audit) {
    // The outbox exists only when messaging is on; without it an open records no event.
    return new OpenPosition(
        counterparties,
        securities,
        positions,
        trades,
        calendar,
        outbox.getIfAvailable(() -> Outbox.NONE),
        clock,
        metrics.getIfAvailable(() -> LedgerMetrics.NONE),
        audit);
  }

  /** Stage 13. Booked lines join the booking's transaction; refusals get their own. */
  @Bean
  Audit audit(AuditStore store, ObjectProvider<Callers> callers, Clock clock) {
    return new Audit(store, callers.getIfAvailable(() -> Callers.SYSTEM_ONLY), clock);
  }

  @Bean
  ListAudit listAudit(AuditQuery query) {
    return new ListAudit(query);
  }

  /** Stage 14. Raising a rollout is two people and observed steps; lowering is one person. */
  @Bean
  ManageRollout manageRollout(
      RolloutStore store,
      RolloutProperties properties,
      Audit audit,
      ObjectProvider<Callers> callers,
      Clock clock) {
    return new ManageRollout(
        store, properties.rules(), audit, callers.getIfAvailable(() -> Callers.SYSTEM_ONLY), clock);
  }

  @Bean
  OpenPositionSteps openPositionSteps(
      CreditReservations reservations, CollateralHolds holds, OpenPosition open) {
    return new TransactionalOpenPositionSteps(reservations, holds, open);
  }

  @Bean
  OpenPositionSaga openPositionSaga(
      OpenPositionSteps steps,
      SagaLog log,
      FindBookedTrade booked,
      ObjectProvider<FailurePoint> failures,
      @Value("${ledger.saga.completion-attempts:3}") int completionAttempts) {
    return new OpenPositionSaga(
        steps, log, booked, failures.getIfAvailable(() -> FailurePoint.NONE), completionAttempts);
  }

  @Bean
  SagaRecovery sagaRecovery(SagaLog log, OpenPositionSaga saga) {
    return new SagaRecovery(log, saga);
  }

  @Bean
  ReturnPositionOnce returnPositionOnce(
      LoanPositionRepository positions,
      SecurityDirectory securities,
      TradeLedger trades,
      HolidayCalendar calendar,
      Audit audit) {
    return new ReturnPositionOnce(positions, securities, trades, calendar, audit);
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
      OpenPosition open, ReturnPosition returns, FindBookedTrade booked, Audit audit) {
    return new IdempotentBooking(open, returns, booked, audit);
  }

  @Bean
  LastKnownPrices lastKnownPrices(
      @Value("${ledger.quote.last-known-capacity:10000}") int capacity) {
    return LastKnownPrices.bounded(capacity);
  }

  @Bean
  QuotePrice quotePrice(
      SecurityDirectory securities,
      MarketPriceSource source,
      ObjectProvider<CachedQuoteSources> cached,
      LastKnownPrices lastKnown,
      Clock clock,
      @Value("${ledger.quote.stale-max-age:15m}") Duration staleMaxAge,
      ObjectProvider<LedgerMetrics> metrics) {
    // The cached lookups are for quotes only: a credit check must not read a cached price. The
    // same goes for a stale one: the last-known prices are given to QuotePrice and to nothing else.
    CachedQuoteSources sources = cached.getIfAvailable();
    LedgerMetrics meters = metrics.getIfAvailable(() -> LedgerMetrics.NONE);
    return sources == null
        ? new QuotePrice(securities, source, lastKnown, clock, staleMaxAge, meters)
        : new QuotePrice(
            sources.securities(), sources.prices(), lastKnown, clock, staleMaxAge, meters);
  }

  @Bean
  PriceFanOut priceFanOut(
      QuotePrice quote, MarketDataPool pool, MarketDataExecutorProperties executor) {
    return new PriceFanOut(quote, pool, executor.deadline());
  }

  @Bean
  ListPositions listPositions(PositionListQuery query, PositionCursorQuery cursor) {
    return new ListPositions(query, cursor);
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
