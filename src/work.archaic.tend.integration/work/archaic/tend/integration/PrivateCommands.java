package work.archaic.tend.integration;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded observations whose bytes and provider causes must never enter public command evidence. */
final class PrivateCommands {
    private final Path directory;
    PrivateCommands(Path directory) { this.directory = directory; }
    IncusCommands.Result run(Duration timeout, List<String> arguments) throws IOException, InterruptedException {
        Path output = Files.createTempFile(directory, "private-observation-", ".txt", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IOException("Private observation timed out");
            return new IncusCommands.Result(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
            Files.deleteIfExists(output);
        }
    }
    String incus(String... args) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("sudo", "-n", "incus")); command.addAll(List.of(args));
        var result = run(Duration.ofSeconds(30), command);
        if (result.status() != 0) throw new IOException("Private Incus observation failed; output withheld");
        return result.output();
    }
    void file(Path path, String value) throws IOException {
        if (!Files.exists(path)) Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(path, value);
    }
}
