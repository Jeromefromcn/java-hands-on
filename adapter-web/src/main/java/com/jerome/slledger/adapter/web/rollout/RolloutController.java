package com.jerome.slledger.adapter.web.rollout;

import com.jerome.slledger.adapter.web.security.Access;
import com.jerome.slledger.application.rollout.ManageRollout;
import com.jerome.slledger.application.rollout.Rollout;
import com.jerome.slledger.application.rollout.RolloutChange;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 14: the rollout of a change, for operations staff. A raise is proposed by one person and
 * approved by another ({@code 409 rollout-same-person} otherwise); a lowering, the kill switch
 * included, is one call. Refusals are {@code 409} problems whose type names the rule. See
 * docs/decisions/14-delivery.md.
 */
@RestController
@RequestMapping("/rollouts/{flag}")
public class RolloutController {

  private final ManageRollout rollouts;

  RolloutController(ManageRollout rollouts) {
    this.rollouts = rollouts;
  }

  public record RolloutView(
      String flag, int percent, Instant effectiveSince, List<ChangeView> changes) {}

  public record ChangeView(
      long id,
      int fromPercent,
      int toPercent,
      String proposedBy,
      Instant proposedAt,
      String status,
      String decidedBy,
      Instant decidedAt) {
    static ChangeView of(RolloutChange change) {
      return new ChangeView(
          change.id(),
          change.fromPercent(),
          change.toPercent(),
          change.proposedBy(),
          change.proposedAt(),
          change.status().name(),
          change.decidedBy().orElse(null),
          change.decidedAt().orElse(null));
    }
  }

  /** The percentage to go to. */
  public record PercentRequest(@Min(0) @Max(100) int toPercent) {}

  @GetMapping
  @Access.ManageRollouts
  public RolloutView show(@PathVariable String flag) {
    return view(rollouts.show(flag), flag);
  }

  @PostMapping("/raises")
  @ResponseStatus(HttpStatus.CREATED)
  @Access.ManageRollouts
  public ChangeView proposeRaise(
      @PathVariable String flag, @Valid @RequestBody PercentRequest request) {
    return ChangeView.of(rollouts.proposeRaise(flag, request.toPercent()));
  }

  @PostMapping("/raises/{id}/approval")
  @Access.ManageRollouts
  public RolloutView approve(@PathVariable String flag, @PathVariable long id) {
    return view(rollouts.approve(flag, id), flag);
  }

  @PostMapping("/lowering")
  @Access.ManageRollouts
  public RolloutView lower(@PathVariable String flag, @Valid @RequestBody PercentRequest request) {
    return view(rollouts.lower(flag, request.toPercent()), flag);
  }

  private RolloutView view(Rollout rollout, String flag) {
    return new RolloutView(
        rollout.flag(),
        rollout.percent(),
        rollout.effectiveSince(),
        rollouts.changes(flag, 20).stream().map(ChangeView::of).toList());
  }
}
