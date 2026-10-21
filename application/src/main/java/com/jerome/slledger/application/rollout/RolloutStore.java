package com.jerome.slledger.application.rollout;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Where rollouts and their changes are kept. Joins whatever transaction the caller is in. */
public interface RolloutStore {

  Optional<Rollout> find(String flag);

  RolloutChange propose(String flag, int fromPercent, int toPercent, String by, Instant at);

  Optional<RolloutChange> change(String flag, long id);

  /**
   * Sets the rollout to {@code percent}, effective {@code at}, if it is still at {@code
   * current.version()}.
   *
   * @return false when it moved since {@code current} was read; nothing is changed then
   */
  boolean move(Rollout current, int percent, Instant at);

  /** Marks a proposed change applied by {@code by}. */
  void applied(long changeId, String by, Instant at);

  /** Marks every change of the flag still proposed as superseded: the rollout has moved. */
  void supersedeOpen(String flag, Instant at);

  /** The flag's changes, newest first. */
  List<RolloutChange> changes(String flag, int limit);
}
