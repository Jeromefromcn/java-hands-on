package labs.messaging;

/** First line of every lab's output: what machine and JVM the numbers came from. */
final class Env {

  private Env() {}

  static String describe() {
    return "JVM %s (%s), %d CPUs, %s %s"
        .formatted(
            System.getProperty("java.version"),
            System.getProperty("java.vm.name"),
            Runtime.getRuntime().availableProcessors(),
            System.getProperty("os.name"),
            System.getProperty("os.version"));
  }
}
