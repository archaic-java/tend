package work.archaic.tend.test;

import java.nio.file.*;
import java.time.Duration;
import work.archaic.tend.Main;

/** Child-process fixture: interrupt the actual CLI thread after its first complete pass. */
public final class InterruptionProbe {
    private InterruptionProbe() {}
    public static void main(String... args) {
        Thread controller = Thread.currentThread();
        Path success = Path.of(args[5]).resolve("last-success");
        Thread.ofPlatform().daemon().start(() -> interruptAfterSuccess(controller, success));
        Main.main(args);
    }
    private static void interruptAfterSuccess(Thread controller, Path success) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        try {
            while (!Files.exists(success) && System.nanoTime() < deadline) Thread.sleep(25);
            controller.interrupt();
        } catch (InterruptedException interruption) { Thread.currentThread().interrupt(); }
    }
}
