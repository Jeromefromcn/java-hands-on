package com.jerome.slledger.application.reconciliation;

import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/** Records a chunk of breaks in the chunk's transaction. */
public class SaveBreaks {

  private final ReconBreaks breaks;

  public SaveBreaks(ReconBreaks breaks) {
    this.breaks = breaks;
  }

  @Transactional
  public int save(List<ReconBreak> chunk) {
    return breaks.save(chunk);
  }
}
