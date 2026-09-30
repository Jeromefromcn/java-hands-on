package labs.concurrency;

/** The header every lab prints first: a result means nothing without the JVM and CPU it came from. */
final class Env {

  private Env() {}

  static String header(String lab) {
    return "%s%njava.version=%s  vm=%s%narch=%s  cpus=%d%n"
        .formatted(
            lab,
            System.getProperty("java.version"),
            System.getProperty("java.vm.name"),
            System.getProperty("os.arch"),
            Runtime.getRuntime().availableProcessors());
  }
}
