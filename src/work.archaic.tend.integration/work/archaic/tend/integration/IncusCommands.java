package work.archaic.tend.integration;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded local CLI calls, with complete output retained in the CI artifact. */
final class IncusCommands {
    private final Path evidence = Path.of("out/incus-smoke");
    private int sequence;
    IncusCommands() throws IOException {
        if (!"yes".equals(System.getenv("TEND_DISPOSABLE_RUNNER")))
            throw new IOException("Integration tests require an explicitly disposable runner");
        Files.createDirectories(evidence);
    }
    record Result(int status, String output) {}
    Result run(Duration timeout, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("sudo", "-n", "incus"));
        command.addAll(List.of(arguments));
        Path output = evidence.resolve("command-%03d.log".formatted(++sequence));
        Files.writeString(evidence.resolve("commands.txt"), output.getFileName() + " " + command + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IOException("Incus command timed out: " + output);
            return new Result(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
        }
    }
    void require(String... arguments) throws IOException, InterruptedException {
        var result = run(Duration.ofSeconds(90), arguments);
        if (result.status() != 0) throw new IOException("Incus command failed: " + result.output());
    }
    Result request(int port) throws IOException, InterruptedException {
        return run(Duration.ofSeconds(8), "exec", "tend-ci-client", "--", "/root/probe", "wget", "-T", "2", "-O", "-",
                "http://10.77.1.11:" + port + "/");
    }
    Result reachable(int port) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        Result result;
        do {
            result = request(port);
            if (result.status() == 0 && result.output().contains("tend-ovn-smoke")) return result;
            Thread.sleep(Duration.ofMillis(300));
        } while (System.nanoTime() < deadline);
        return result;
    }
    Result rejected(int port) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Result result;
        do {
            result = request(port);
            if (result.status() == 1 && (result.output().contains("Connection refused") || result.output().contains("timed out"))) return result;
            Thread.sleep(Duration.ofMillis(300));
        } while (System.nanoTime() < deadline);
        return result;
    }
}
