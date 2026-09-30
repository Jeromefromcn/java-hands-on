package labs.concurrency;

/**
 * A reader spins on a plain boolean; a writer sets it. Nothing tells the JIT the field can change
 * under the reader, so once the loop is compiled the read is hoisted out of it and the reader never
 * sees the write.
 *
 * <p>Each round is bounded by a wall-clock limit. A round whose reader has not stopped by then is
 * counted as failed and its thread is abandoned (a daemon, still spinning): there is no way to stop
 * a thread that never re-reads its condition. That is the failure, not a harness shortcut.
 *
 * <p>Whether it happens depends on the JIT and on the CPU, not only on the code. The output states
 * both, and how many of N rounds failed, including zero.
 */
public final class Visibility {

  static boolean plainStop;
  static volatile boolean volatileStop;

  public static void main(String[] args) throws Exception {
    int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 10;
    long limitMillis = 1000;
    System.out.print(Env.header("Visibility — a stop flag with and without volatile"));
    System.out.printf("rounds=%d, each bounded at %d ms after the write%n%n", rounds, limitMillis);

    int plainHung = 0;
    for (int round = 1; round <= rounds; round++) {
      plainStop = false;
      Thread reader =
          new Thread(
              () -> {
                long spins = 0;
                while (!plainStop) {
                  spins++;
                }
                if (spins < 0) {
                  System.out.println(spins);
                }
              });
      reader.setDaemon(true);
      reader.start();
      Thread.sleep(200); // let the loop be compiled; this pause is the point, not sequencing
      plainStop = true;
      reader.join(limitMillis);
      boolean hung = reader.isAlive();
      if (hung) {
        plainHung++;
      }
      System.out.printf("plain    round %2d: %s%n", round, hung ? "reader never saw the write" : "stopped");
    }

    int volatileHung = 0;
    for (int round = 1; round <= rounds; round++) {
      volatileStop = false;
      Thread reader =
          new Thread(
              () -> {
                long spins = 0;
                while (!volatileStop) {
                  spins++;
                }
                if (spins < 0) {
                  System.out.println(spins);
                }
              });
      reader.setDaemon(true);
      reader.start();
      Thread.sleep(200);
      volatileStop = true;
      reader.join(limitMillis);
      boolean hung = reader.isAlive();
      if (hung) {
        volatileHung++;
      }
      System.out.printf("volatile round %2d: %s%n", round, hung ? "reader never saw the write" : "stopped");
    }

    System.out.printf(
        "%nplain:    %d of %d rounds did not terminate%nvolatile: %d of %d rounds did not terminate%n",
        plainHung, rounds, volatileHung, rounds);
    System.exit(0); // abandoned readers are still spinning
  }
}
