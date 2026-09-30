package labs.caching;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An unbounded Caffeine cache filled until the heap ends. Stage 15's heap experiment starts here:
 * run it under a small {@code -Xmx}, with {@code -XX:+HeapDumpOnOutOfMemoryError} if a dump is
 * wanted (the dump is not committed; the decision record points at it).
 */
public final class UnboundedHeap {

  private UnboundedHeap() {}

  public static void main(String[] args) throws Exception {
    Cache<String, String> cache = Caffeine.newBuilder().build();
    String payload = "x".repeat(100);
    long entries = 0;
    String outcome;
    try {
      while (true) {
        cache.put("SYN" + entries, payload + entries);
        entries++;
      }
    } catch (OutOfMemoryError e) {
      cache.invalidateAll();
      outcome = "OutOfMemoryError: " + e.getMessage();
    }
    String text =
        """
        Stage 9 lab: an unbounded Caffeine cache filled until the heap ends
        Environment: %s
        -Xmx = %d MB (as the JVM reports it)
        entries held when %s

        Each entry: an ISIN-shaped key and a 100-character value, one put per distinct key.
        """
            .formatted(
                Env.describe(), Runtime.getRuntime().maxMemory() / (1024 * 1024), outcome + " -> " + entries);
    Path target = Path.of("target", "evidence");
    Files.createDirectories(target);
    Files.writeString(target.resolve("09-unbounded-heap.txt"), text);
    System.out.println(text);
  }
}
