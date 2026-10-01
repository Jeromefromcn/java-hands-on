package com.jerome.slledger.application.revaluation;

import com.jerome.slledger.application.messaging.IntegrationEvent.MarginCallRaised;
import com.jerome.slledger.application.messaging.Outbox;
import com.jerome.slledger.application.observability.LedgerMetrics;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a chunk's margin calls in the outbox, once each. A marker per (position, price version)
 * is inserted with the outbox row in one transaction, so a chunk that is executed again (after a
 * retry, a restart, or a second instance) finds the marker and raises nothing. Without it, a re-run
 * chunk would send every call twice, and the notice consumer's dedup key (the event id, fresh each
 * time) would not stop it.
 */
public class RaiseMarginCalls {

  private final MarginCallMarkers markers;
  private final Outbox outbox;
  private final LedgerMetrics metrics;

  public RaiseMarginCalls(MarginCallMarkers markers, Outbox outbox) {
    this(markers, outbox, LedgerMetrics.NONE);
  }

  public RaiseMarginCalls(MarginCallMarkers markers, Outbox outbox, LedgerMetrics metrics) {
    this.markers = markers;
    this.outbox = outbox;
    this.metrics = metrics;
  }

  /**
   * @return how many were raised; the rest had been raised before
   */
  @Transactional
  public int raise(List<MarginCallRaised> calls, long priceVersion) {
    int raised = 0;
    for (MarginCallRaised call : calls) {
      if (markers.mark(call.position().value(), priceVersion)) {
        outbox.append(call);
        raised++;
      }
    }
    metrics.marginCallsRaised(raised);
    return raised;
  }
}
