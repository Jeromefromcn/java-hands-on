package com.jerome.slledger.application.revaluation;

import com.jerome.slledger.application.revaluation.RevaluationRequests.Pending;
import java.util.List;

/**
 * Launches the revaluation jobs that price updates asked for. A request for a version the security
 * has since moved past is not launched: the newer update has its own request, or revalued inline,
 * and revaluing at the old version would only send calls that are already out of date.
 */
public class RevaluationDispatcher {

  private final RevaluationRequests requests;
  private final RevaluationLauncher launcher;
  private final int maxAttempts;

  public RevaluationDispatcher(
      RevaluationRequests requests, RevaluationLauncher launcher, int maxAttempts) {
    this.requests = requests;
    this.launcher = launcher;
    this.maxAttempts = maxAttempts;
  }

  /**
   * @return how many requests it launched
   */
  public int dispatch(int limit) {
    int launched = 0;
    List<Pending> pending = requests.pending(limit);
    for (Pending request : pending) {
      long current =
          requests.currentPriceVersion(request.security()).orElse(request.priceVersion());
      if (current > request.priceVersion()) {
        requests.markSuperseded(request.security(), request.priceVersion());
        continue;
      }
      requests.recordAttempt(request.security(), request.priceVersion(), maxAttempts);
      RevaluationLauncher.Result result =
          launcher.launch(request.security(), request.priceVersion());
      launched++;
      if (result == RevaluationLauncher.Result.COMPLETED) {
        requests.markDone(request.security(), request.priceVersion());
      }
    }
    return launched;
  }
}
