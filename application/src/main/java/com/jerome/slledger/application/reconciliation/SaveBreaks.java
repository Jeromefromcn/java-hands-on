package com.jerome.slledger.application.reconciliation;

import java.time.Clock;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/** Records a chunk of breaks in the chunk's transaction. */
public class SaveBreaks {

  private final ReconBreaks breaks;
  private final Clock clock;

  public SaveBreaks(ReconBreaks breaks, Clock clock) {
    this.breaks = breaks;
    this.clock = clock;
  }

  @Transactional
  public int save(List<ReconBreak> chunk) {
    return breaks.save(chunk, clock.instant());
  }
}
