import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Lab 14: fills the heap 16 MB at a time and keeps every block, printing how much it holds against
 * the heap's ceiling. Each block is written to, so its pages are resident, not just reserved. What
 * ends the run is the point: the JVM's OutOfMemoryError, or the kernel's OOM kill of the container.
 */
public class Allocate {

  public static void main(String[] args) throws InterruptedException {
    long max = Runtime.getRuntime().maxMemory();
    System.out.printf(
        "jvm %s, max heap %d MB, gc %s%n",
        Runtime.version(),
        max >> 20,
        ManagementFactory.getGarbageCollectorMXBeans().getFirst().getName());
    List<byte[]> kept = new ArrayList<>();
    for (int mb = 16; ; mb += 16) {
      byte[] block = new byte[16 << 20];
      for (int i = 0; i < block.length; i += 4096) {
        block[i] = 1;
      }
      kept.add(block);
      if (mb % 64 == 0) {
        System.out.printf("holding %d MB%n", mb);
      }
      Thread.sleep(5);
    }
  }
}
