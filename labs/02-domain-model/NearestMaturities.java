import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.TreeMap;

/**
 * "The K positions maturing soonest", three ways.
 *
 * <p>Self-contained on purpose: no dependency on the domain module, no Maven, not in the parent's
 * <modules>. Run it with `java NearestMaturities.java`. It is a measurement harness, not product
 * code, and nothing here is imported by anything.
 *
 * <p>Wall clock on a warm JVM, no JMH. The ranking is the claim; the absolute numbers are
 * indicative and will move between runs. JMH arrives in stage 16.
 */
public final class NearestMaturities {

  private static final int N = 1_000_000;
  private static final int K = 20;
  private static final int QUERIES = 200;
  private static final int WARMUP = 1;
  private static final LocalDate TODAY = LocalDate.of(2026, 3, 2);

  record Position(String id, LocalDate maturity) {}

  public static void main(String[] args) {
    List<Position> positions = generate(N);

    for (int i = 0; i < WARMUP; i++) {
      measure(positions, false);
    }
    System.out.println(header());
    measure(positions, true);
  }

  private static void measure(List<Position> positions, boolean report) {
    // --- 1. TreeMap keyed by maturity ---------------------------------------------------
    long t0 = System.nanoTime();
    NavigableMap<LocalDate, List<Position>> byMaturity = new TreeMap<>();
    for (Position p : positions) {
      byMaturity.computeIfAbsent(p.maturity(), d -> new ArrayList<>()).add(p);
    }
    long treeBuild = System.nanoTime() - t0;

    t0 = System.nanoTime();
    List<Position> treeResult = List.of();
    for (int q = 0; q < QUERIES; q++) {
      treeResult = nearestFromTreeMap(byMaturity, K);
    }
    long treeQuery = System.nanoTime() - t0;

    // --- 2. Bounded PriorityQueue, one pass per query -----------------------------------
    t0 = System.nanoTime();
    long heapBuild = System.nanoTime() - t0;

    t0 = System.nanoTime();
    List<Position> heapResult = List.of();
    for (int q = 0; q < QUERIES; q++) {
      heapResult = nearestFromHeap(positions, K);
    }
    long heapQuery = System.nanoTime() - t0;

    // --- 3. Sorted ArrayList ------------------------------------------------------------
    t0 = System.nanoTime();
    List<Position> sorted = new ArrayList<>(positions);
    sorted.sort(Comparator.comparing(Position::maturity));
    long listBuild = System.nanoTime() - t0;

    t0 = System.nanoTime();
    List<Position> listResult = List.of();
    for (int q = 0; q < QUERIES; q++) {
      listResult = List.copyOf(sorted.subList(0, K));
    }
    long listQuery = System.nanoTime() - t0;

    if (!agree(treeResult, heapResult, listResult)) {
      throw new IllegalStateException("The three structures disagreed; the comparison is void");
    }

    if (report) {
      System.out.println(row("TreeMap<LocalDate, List<>>", treeBuild, treeQuery));
      System.out.println(row("PriorityQueue (bounded K)", heapBuild, heapQuery));
      System.out.println(row("sorted ArrayList", listBuild, listQuery));
      System.out.println();
      System.out.printf(
          "Crossover: the heap keeps nothing, so it wins until the build cost is amortised.%n"
              + "  TreeMap build %.2f ms / heap %.3f ms per query  -> %.1f queries%n"
              + "  sorted  build %.2f ms / heap %.3f ms per query  -> %.1f queries%n%n",
          treeBuild / 1e6,
          heapQuery / 1e6 / QUERIES,
          treeBuild / (double) (heapQuery / QUERIES),
          listBuild / 1e6,
          heapQuery / 1e6 / QUERIES,
          listBuild / (double) (heapQuery / QUERIES));
      System.out.printf(
          "N = %,d positions   K = %d   %,d queries   JVM %s   %s %s%n",
          N,
          K,
          QUERIES,
          System.getProperty("java.version"),
          System.getProperty("os.name"),
          System.getProperty("os.arch"));
    }
  }

  private static List<Position> nearestFromTreeMap(
      NavigableMap<LocalDate, List<Position>> byMaturity, int k) {
    List<Position> out = new ArrayList<>(k);
    for (List<Position> onThatDay : byMaturity.tailMap(TODAY, true).values()) {
      for (Position p : onThatDay) {
        if (out.size() == k) {
          return out;
        }
        out.add(p);
      }
    }
    return out;
  }

  private static List<Position> nearestFromHeap(List<Position> positions, int k) {
    // A max-heap of size k: the largest maturity seen so far sits on top and is evicted when
    // something earlier arrives. One pass, O(N log K), no structure kept between queries.
    PriorityQueue<Position> furthestFirst =
        new PriorityQueue<>(k, Comparator.comparing(Position::maturity).reversed());
    for (Position p : positions) {
      if (p.maturity().isBefore(TODAY)) {
        continue;
      }
      if (furthestFirst.size() < k) {
        furthestFirst.add(p);
      } else if (p.maturity().isBefore(furthestFirst.peek().maturity())) {
        furthestFirst.poll();
        furthestFirst.add(p);
      }
    }
    List<Position> out = new ArrayList<>(furthestFirst);
    out.sort(Comparator.comparing(Position::maturity));
    return out;
  }

  /** The three must return the same maturities, or the timings compare nothing. */
  private static boolean agree(List<Position> a, List<Position> b, List<Position> c) {
    return maturities(a).equals(maturities(b)) && maturities(a).equals(maturities(c));
  }

  private static List<LocalDate> maturities(List<Position> positions) {
    return positions.stream().map(Position::maturity).sorted().toList();
  }

  private static List<Position> generate(int n) {
    Random random = new Random(20260302L); // fixed seed: the same data every run
    List<Position> positions = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      positions.add(new Position("LP-%07d".formatted(i), TODAY.plusDays(random.nextInt(3_650))));
    }
    return positions;
  }

  private static String header() {
    return """
        %-28s %14s %14s
        %s"""
        .formatted("structure", "build (ms)", "queries (ms)", "-".repeat(58));
  }

  private static String row(String name, long buildNanos, long queryNanos) {
    return "%-28s %14.2f %14.2f".formatted(name, buildNanos / 1e6, queryNanos / 1e6);
  }
}
