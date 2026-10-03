package work.archaic.tend.integration;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Private disposable Git/CLI fixture; verification remains inline in the Minau case. */
final class RealGarden implements AutoCloseable {
    final Path directory = Files.createTempDirectory("tend-ci-garden-");
    private final Path author = directory.resolve("author");
    private final Path remote = directory.resolve("remote.git");
    private final Path state = directory.resolve("state");
    private final IncusCommands commands;
    private final String fingerprint;
    RealGarden(IncusCommands commands) throws IOException, InterruptedException {
        this.commands = commands;
        fingerprint = Files.readString(Path.of("out/incus-smoke/fingerprint.txt")).strip();
        if (!fingerprint.matches("[a-f0-9]{64}")) throw new IOException("Expected resolved Incus image fingerprint");
        Files.createDirectories(author);
        command("git", "init", "--bare", "--initial-branch=main", remote.toString());
        git("init", "--initial-branch=main");
        git("config", "user.name", "Tend integration test");
        git("config", "user.email", "tend-test@example.invalid");
        git("remote", "add", "origin", remote.toString());
    }
    String commit(String version) throws IOException, InterruptedException {
        Files.writeString(author.resolve("incus.xml"), xml(version));
        Files.writeString(author.resolve("service.conf"), "version=" + version + "\n");
        git("add", "."); git("commit", "-m", "Desired state " + version); git("push", "origin", "main");
        return git("rev-parse", "HEAD").strip();
    }
    void reconcile() throws IOException, InterruptedException {
        String credentials = Files.readString(Path.of("out/incus-smoke/credentials-directory.txt")).strip();
        command(Path.of(System.getProperty("java.home"), "bin/java").toString(), "@" + credentials + "/java.args",
                "@cmd/run", "once", remote.toString(), "https://127.0.0.1:8443", "default", "tend-ci-controller", state.toString());
    }
    void recordObservedState() throws IOException, InterruptedException {
        incus("config", "show", "tend-ci-managed", "--format=json");
        String credentials = Files.readString(Path.of("out/incus-smoke/credentials-directory.txt")).strip();
        command("curl", "-fsSI", "--cert", credentials + "/client.crt", "--key", credentials + "/client.key",
                "--cacert", credentials + "/server.crt",
                "https://127.0.0.1:8443/1.0/storage-pools/tend-ci-pool/volumes/custom/tend-ci-data/files?path=/service.conf&project=default");
    }
    String lastSuccess() throws IOException { return Files.readString(state.resolve("last-success")).strip(); }
    String config(String key) throws IOException, InterruptedException {
        return incus("config", "get", "tend-ci-managed", key).strip();
    }
    String volumeOwner() throws IOException, InterruptedException {
        return incus("storage", "volume", "get", "tend-ci-pool", "tend-ci-data", "user.tend.owner").strip();
    }
    String file() throws IOException, InterruptedException { return incus("exec", "tend-ci-managed", "--", "cat", "/data/service.conf"); }
    String permissions() throws IOException, InterruptedException {
        return incus("exec", "tend-ci-managed", "--", "stat", "-c", "%u:%g:%a", "/data/service.conf").strip();
    }
    String started() throws IOException, InterruptedException {
        String stat = incus("exec", "tend-ci-managed", "--", "cat", "/proc/1/stat");
        // Fields after the parenthesized command begin at field 3; start time is field 22.
        return stat.substring(stat.lastIndexOf(')') + 2).split("\\s+")[19];
    }
    private String incus(String... args) throws IOException, InterruptedException {
        var result = commands.run(Duration.ofSeconds(90), args);
        if (result.status() != 0) throw new IOException("Incus fixture command failed: " + result.output());
        return result.output();
    }
    private String git(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", author.toString())); command.addAll(List.of(args));
        return command(command.toArray(String[]::new));
    }
    private String command(String... args) throws IOException, InterruptedException {
        var result = commands.command(Duration.ofMinutes(3), List.of(args));
        if (result.status() != 0) throw new IOException("Fixture command failed: " + result.output());
        return result.output();
    }
    private String xml(String version) {
        return """
                <incus project="default">
                  <volume pool="tend-ci-pool" name="tend-ci-data">
                    <file path="/service.conf" source="service.conf" uid="1000" gid="1000" mode="0640"/>
                  </volume>
                  <instance name="tend-ci-managed" fingerprint="%s">
                    <config><entry key="environment.DEMO" value="%s"/></config>
                    <device name="root" type="disk"><config>
                      <entry key="path" value="/"/><entry key="pool" value="tend-ci-pool"/>
                    </config></device>
                    <device name="data" type="disk"><config>
                      <entry key="path" value="/data"/><entry key="pool" value="tend-ci-pool"/>
                      <entry key="source" value="tend-ci-data"/>
                    </config></device>
                  </instance>
                </incus>
                """.formatted(fingerprint, version);
    }
    public void close() throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
