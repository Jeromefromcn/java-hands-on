package com.jerome.slledger.bootstrap.position;

import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanPositionRepository;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only. Wraps the real repository and, while armed, holds each {@code find} until {@code
 * parties} finds have all loaded. That is the interleaving an optimistic conflict needs: every
 * caller has read the same version before any of them writes. No production class has a hook for
 * it.
 *
 * <p>Armed for a fixed number of finds only, so a retry after the conflict passes straight through.
 */
public class BarrierAfterFind implements LoanPositionRepository {

  private final LoanPositionRepository delegate;
  private final AtomicInteger armed = new AtomicInteger();
  private volatile CyclicBarrier barrier;

  public BarrierAfterFind(LoanPositionRepository delegate) {
    this.delegate = delegate;
  }

  /** The next {@code parties} finds wait for each other; later finds do not wait. */
  public void arm(int parties) {
    barrier = new CyclicBarrier(parties);
    armed.set(parties);
  }

  @Override
  public Optional<LoanPosition> find(LoanPositionId id) {
    Optional<LoanPosition> found = delegate.find(id);
    if (armed.getAndDecrement() > 0) {
      try {
        barrier.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      } catch (BrokenBarrierException | TimeoutException e) {
        throw new IllegalStateException("The other find never arrived", e);
      }
    }
    return found;
  }

  @Override
  public List<LoanPosition> activeOf(CounterpartyId counterparty) {
    return delegate.activeOf(counterparty);
  }

  @Override
  public void add(LoanPosition position) {
    delegate.add(position);
  }
}
