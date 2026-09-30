package com.jerome.slledger.adapter.persistence.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.adapter.persistence.instrument.SecurityMapper;
import com.jerome.slledger.adapter.persistence.party.CounterpartyMapper;
import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.instrument.Security;
import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanPositionRepository;
import com.jerome.slledger.domain.loan.LoanStatus;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.Counterparty;
import com.jerome.slledger.domain.party.CounterpartyId;
import com.jerome.slledger.domain.party.CreditLimit;
import jakarta.persistence.EntityManagerFactory;
import java.util.Currency;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What every {@link LoanPositionRepository} implementation promises, whatever its lock strategy.
 * Section 4 runs it against the optimistic adapter, section 12 against the pessimistic one; the
 * adapter is selected by {@code ledger.persistence.locking}, as in the application.
 */
abstract class LoanPositionRepositoryContract {

  static final Currency HKD = Currency.getInstance("HKD");
  static final CounterpartyId CP1 = new CounterpartyId("CP-001");
  static final CounterpartyId CP2 = new CounterpartyId("CP-002");
  static final Isin ISIN = new Isin("HK0000000001");

  ConfigurableApplicationContext context;
  LoanPositionRepository repository;
  TransactionTemplate tx;
  JdbcClient jdbc;

  /** Beans to add to the context besides what PersistenceConfiguration scans. */
  Class<?>[] beans() {
    return new Class<?>[0];
  }

  /** Properties for the context; selects the lock strategy under test. */
  String[] properties() {
    return new String[0];
  }

  @BeforeEach
  void start() {
    context = LedgerDb.start(properties(), beans());
    repository = context.getBean(LoanPositionRepository.class);
    tx = context.getBean(TransactionTemplate.class);
    jdbc = context.getBean(JdbcClient.class);
    EntityManagerFactory emf = context.getBean(EntityManagerFactory.class);
    inTx(
        () -> {
          var em = EntityManagerFactoryUtils.getTransactionalEntityManager(emf);
          for (CounterpartyId id : new CounterpartyId[] {CP1, CP2}) {
            em.persist(
                CounterpartyMapper.toEntity(
                    new Counterparty(
                        id,
                        "Synthetic Borrower " + id.value(),
                        new CreditLimit(Money.of("100000000.00", HKD)))));
          }
          em.persist(
              SecurityMapper.toEntity(
                  new Security(ISIN, "Synthetic Equity", HKD, Money.of("41.26", HKD))));
          return null;
        });
  }

  @AfterEach
  void stop() {
    context.close();
  }

  @Test
  void aPositionAddedIsFoundAgainInALaterTransaction() {
    inTx(() -> add(positionOf("P1", CP1, 100)));

    LoanPosition found = inTx(() -> repository.find(new LoanPositionId("P1")).orElseThrow());

    assertThat(found.id()).isEqualTo(new LoanPositionId("P1"));
    assertThat(found.status()).isEqualTo(LoanStatus.OPEN);
    assertThat(found.outstandingQuantity()).isEqualTo(100);
    assertThat(found.collateral()).isEqualTo(Money.of("4126.00", HKD));
  }

  @Test
  void aReturnWrittenBackIsWhatTheNextTransactionReads() {
    inTx(() -> add(positionOf("P1", CP1, 100)));

    inTx(
        () -> {
          LoanPosition position = repository.find(new LoanPositionId("P1")).orElseThrow();
          position.returnQuantity(30);
          return add(position);
        });

    LoanPosition after = inTx(() -> repository.find(new LoanPositionId("P1")).orElseThrow());
    assertThat(after.outstandingQuantity()).isEqualTo(70);
    assertThat(after.status()).isEqualTo(LoanStatus.PARTIALLY_RETURNED);
  }

  @Test
  void aChangeNeverPassedBackToAddIsNotStored() {
    // The aggregate is not the persistence model: nothing observes it changing.
    inTx(() -> add(positionOf("P1", CP1, 100)));

    inTx(
        () -> {
          repository.find(new LoanPositionId("P1")).orElseThrow().returnQuantity(30);
          return null;
        });

    assertThat(outstanding("P1")).isEqualTo(100);
  }

  @Test
  void activeOfLeavesOutClosedPositionsAndOtherCounterparties() {
    inTx(
        () -> {
          add(positionOf("P1", CP1, 100));
          add(positionOf("P2", CP1, 100));
          return add(positionOf("P3", CP2, 100));
        });
    inTx(
        () -> {
          LoanPosition p2 = repository.find(new LoanPositionId("P2")).orElseThrow();
          p2.returnQuantity(100);
          return add(p2);
        });

    var active = inTx(() -> repository.activeOf(CP1));

    assertThat(active).extracting(LoanPosition::id).containsExactly(new LoanPositionId("P1"));
  }

  @Test
  void nothingIsDoneOutsideATransaction() {
    inTx(() -> add(positionOf("P1", CP1, 100)));

    assertThatExceptionOfType(IllegalTransactionStateException.class)
        .isThrownBy(() -> repository.add(positionOf("P2", CP1, 100)));
    assertThatExceptionOfType(IllegalTransactionStateException.class)
        .isThrownBy(() -> repository.find(new LoanPositionId("P1")));
  }

  /** Review focus 1: the overwrite the naive shape lets through, refused here. */
  @Test
  void aPositionLoadedInAnEarlierTransactionCannotBeWrittenBackInALaterOne() {
    inTx(() -> add(positionOf("P1", CP1, 100)));
    LoanPosition loadedEarlier =
        inTx(() -> repository.find(new LoanPositionId("P1")).orElseThrow());

    assertThatExceptionOfType(PositionNotLoadedInThisTransactionException.class)
        .isThrownBy(
            () ->
                inTx(
                    () -> {
                      loadedEarlier.returnQuantity(10);
                      return add(loadedEarlier);
                    }))
        .withMessageContaining("P1");
    assertThat(outstanding("P1")).isEqualTo(100);
  }

  @Test
  void aNewInstanceWithAnIdAlreadyStoredIsRefusedNotMerged() {
    inTx(() -> add(positionOf("P1", CP1, 100)));

    assertThatExceptionOfType(PositionNotLoadedInThisTransactionException.class)
        .isThrownBy(() -> inTx(() -> add(positionOf("P1", CP1, 999))));
    assertThat(outstanding("P1")).isEqualTo(100);
  }

  long outstanding(String id) {
    return jdbc.sql("SELECT outstanding_quantity FROM loan_position WHERE id = ?")
        .param(id)
        .query(Long.class)
        .single();
  }

  <T> T inTx(Supplier<T> work) {
    return tx.execute(status -> work.get());
  }

  Void add(LoanPosition position) {
    repository.add(position);
    return null;
  }

  static LoanPosition positionOf(String id, CounterpartyId counterparty, long quantity) {
    return LoanPosition.open(
        new LoanPositionId(id),
        counterparty,
        ISIN,
        quantity,
        Money.of("4126.00", HKD),
        new FlatRate(Rate.of("0.0365")));
  }
}
