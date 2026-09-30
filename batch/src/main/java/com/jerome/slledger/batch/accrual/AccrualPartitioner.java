package com.jerome.slledger.batch.accrual;

import com.jerome.slledger.application.accrual.AccrualCandidates;
import com.jerome.slledger.application.accrual.PartitionScheme;
import com.jerome.slledger.application.accrual.Slice;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * Cuts the accrual's keyspace into slices, one execution context each. A partition's reader reads
 * only what its context names, so a partition scheme is a way of writing three keys into a map: the
 * counterparty, the first id and the last id of the slice.
 *
 * <p>The slices come from the store, not from a formula: only it knows how the positions are
 * distributed, and how they are distributed is what decides whether the slices take equally long.
 * {@code gridSize} is ignored on purpose; the scheme decides how many slices there are.
 */
public final class AccrualPartitioner implements Partitioner {

  /** Context keys a partition's reader looks for. */
  public static final String COUNTERPARTY = "slice.counterparty";

  public static final String ID_FROM = "slice.idFrom";
  public static final String ID_TO = "slice.idTo";

  private final AccrualCandidates candidates;
  private final LocalDate businessDate;
  private final PartitionScheme scheme;
  private final int parts;

  public AccrualPartitioner(
      AccrualCandidates candidates, LocalDate businessDate, PartitionScheme scheme, int parts) {
    this.candidates = candidates;
    this.businessDate = businessDate;
    this.scheme = scheme;
    this.parts = parts;
  }

  @Override
  public Map<String, ExecutionContext> partition(int gridSize) {
    List<Slice> slices = candidates.slices(scheme, businessDate, parts);
    Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
    for (int i = 0; i < slices.size(); i++) {
      Slice slice = slices.get(i);
      ExecutionContext context = new ExecutionContext();
      slice.counterparty().ifPresent(value -> context.putString(COUNTERPARTY, value));
      slice.idFrom().ifPresent(value -> context.putString(ID_FROM, value));
      slice.idTo().ifPresent(value -> context.putString(ID_TO, value));
      partitions.put("partition%d".formatted(i), context);
    }
    return partitions;
  }
}
