package work.archaic.tend.git;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Fetches main without checkout; every file is read from one immutable commit. */
public final class GitRepository {
    private final Path mirror;
    private final String remote;

    public GitRepository(Path mirror, String remote) {
        this.mirror = mirror.toAbsolutePath();
        this.remote = Objects.requireNonNull(remote);
        if (remote.isBlank() || remote.startsWith("-")) throw new IllegalArgumentException("Invalid Git remote");
    }

    public synchronized Revision fetchMain() throws IOException, InterruptedException {
        if (!Files.exists(mirror.resolve("HEAD"))) {
            Files.createDirectories(mirror);
            command(mirror, "init", "--bare");
        }
        command(mirror, "fetch", "--no-tags", "--force", "--", remote,
                "+refs/heads/main:refs/tend/main");
        String commit = new String(command(mirror, "rev-parse", "--verify", "refs/tend/main^{commit}"),
                java.nio.charset.StandardCharsets.UTF_8).strip();
        return new Revision(mirror, commit);
    }

    public record Revision(Path repository, String commit) {
        public byte[] read(String path) throws IOException, InterruptedException {
            if (path.isBlank() || path.startsWith("/") || path.contains("\\") ||
                    Arrays.asList(path.split("/", -1)).stream().anyMatch(p -> p.equals("..") || p.isEmpty()))
                throw new IOException("Invalid repository path");
            // Reject symlinks/submodules; git show reads their object, not their target.
            String entry = new String(command(repository, "ls-tree", commit, "--", path),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!(entry.startsWith("100644 blob ") || entry.startsWith("100755 blob ")))
                throw new IOException("Expected regular repository file: " + path);
            return command(repository, "show", commit + ":" + path);
        }
    }

    /** Bounded, noninteractive Git process; stdout and stderr remain separate. */
    public static byte[] command(Path directory, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null"));
        command.addAll(List.of(arguments));
        Path output = Files.createTempFile("tend-git-", ".out");
        Path error = Files.createTempFile("tend-git-", ".err");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectOutput(output.toFile()).redirectError(error.toFile());
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = builder.start();
            if (!process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS))
                throw new IOException("Git operation timed out");
            if (process.exitValue() != 0) throw new IOException("Git operation failed (exit " + process.exitValue() + ")");
            if (Files.size(output) > 8 * 1024 * 1024) throw new IOException("Repository file exceeds 8 MiB");
            return Files.readAllBytes(output);
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
            Files.deleteIfExists(output);
            Files.deleteIfExists(error);
        }
    }
}
