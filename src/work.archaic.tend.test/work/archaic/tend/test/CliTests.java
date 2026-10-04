package work.archaic.tend.test;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import work.archaic.service.test.v02.*;

public record CliTests() implements TestSuite {
    public void cases(Collection<TestCase> cases) {
        cases.add(new CliReconciles());
        cases.add(new CliPrivateLaunchFile());
        cases.add(new CliRejectsInvalidRevision());
        cases.add(new CliWatchRecovers());
        cases.add(new CliInterrupted());
        cases.add(new CliRejectsArguments());
        cases.add(new CliMissingProvider());
    }
}
record CliReconciles() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden(); IncusMock mock = new IncusMock()) {
            String revision = garden.commit(Garden.xml(), "cli");
            assert CliProcess.invoke(garden, mock) == 0 : "CLI composition must reconcile using real adapters and logging providers";
            assert Files.readString(garden.state.resolve("last-success")).strip().equals(revision) : "CLI records success only for a complete revision";
            String output = Files.readString(garden.directory.resolve("cli-output"));
            String secret = Files.readString(garden.state.resolve("secrets/session"));
            assert !output.contains(secret) : "CLI output must not expose generated secret values";
            assert output.contains("Reconciled " + revision) : "Successful context must publish completion";
            assert !output.contains("Reconciling commit") && !output.contains("Desired resources:") : "Success must discard failure evidence and disabled debug output";
            assert Files.size(garden.directory.resolve("cli-stdout")) == 0 : "Controller logs must use stderr";
        }
    }
}
record CliRejectsInvalidRevision() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden(); IncusMock mock = new IncusMock()) {
            String revision = garden.commit(Garden.xml(), "cli");
            assert CliProcess.invoke(garden, mock) == 0 : "Initial revision must succeed";
            int mutations = mock.mutations;
            garden.commit("<incus project=\"garden\"><unknown/></incus>", "invalid");
            assert CliProcess.invoke(garden, mock) == 1 : "Invalid XML must fail the CLI pass";
            assert mock.mutations == mutations : "Invalid revision cannot mutate deployed resources";
            assert Files.readString(garden.state.resolve("last-success")).strip().equals(revision) : "Failed revision must preserve last successful status";
            String output = Files.readString(garden.directory.resolve("cli-output"));
            assert output.lines().filter(line -> line.equals("--- failed logging context ---")).count() == 1 : "Once failure must render exactly one context report";
            assert output.lines().filter(line -> line.startsWith("work.archaic.tend.ControllerFailure:")).count() == 1 : "Outer CLI handling must not print the same failure again";
            assert Files.size(garden.directory.resolve("cli-stdout")) == 0 : "Failure reports must use stderr";
        }
    }
}
record CliWatchRecovers() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden(); IncusMock mock = new IncusMock()) {
            garden.commit("<incus project=\"garden\"><unknown/></incus>", "invalid");
            Process process = CliProcess.start(garden, mock, "watch", true);
            try {
                CliProcess.awaitText(process, garden.directory.resolve("cli-output"), "--- end context ---");
                String revision = garden.commit(Garden.xml(), "recovered");
                CliProcess.awaitText(process, garden.state.resolve("last-success"), revision);
                String next = garden.commit(Garden.xml(), "next");
                CliProcess.awaitText(process, garden.state.resolve("last-success"), next);
                CliProcess.awaitText(process, garden.directory.resolve("cli-output"), "Reconciled " + next);
                assert process.isAlive() : "Watch must recover and keep polling after a failed attempt";
                String output = Files.readString(garden.directory.resolve("cli-output"));
                assert output.contains("Desired resources:") : "Configured debug must publish on the polling thread";
                assert !output.contains("Logging context is single-use") : "Every polling attempt must own a fresh context";
                assert !output.contains(Files.readString(garden.state.resolve("secrets/session"))) : "Debug and failure output must exclude secret values";
                assert Files.size(garden.directory.resolve("cli-stdout")) == 0 : "Debug must respect the configured stderr destination";
            } finally { CliProcess.stop(process); }
        }
    }
}
record CliInterrupted() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden(); IncusMock mock = new IncusMock()) {
            String revision = garden.commit(Garden.xml(), "interruption");
            var command = new ArrayList<>(List.of(CliProcess.java(), "--module-path", "out:lib/bin", "--add-modules", "work.archaic.culpa",
                    "-m", "work.archaic.tend.test/work.archaic.tend.test.InterruptionProbe"));
            command.addAll(CliProcess.arguments(garden, mock, "watch"));
            Process process = CliProcess.launch(garden, command);
            try {
                assert CliProcess.finish(process) == 130 : "Thread interruption must escape watch retries with interruption exit status";
                assert Files.readString(garden.state.resolve("last-success")).strip().equals(revision) : "Interrupting polling must retain completed reconciliation";
            } finally { CliProcess.stop(process); }
        }
    }
}
record CliRejectsArguments() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden(); IncusMock mock = new IncusMock()) {
            Path rejected = garden.directory.resolve("rejected-state");
            var command = new ArrayList<>(List.of(CliProcess.java(), "@cmd/run"));
            command.addAll(List.of("once", garden.remote.toString(), mock.endpoint().toString(), "garden", "owner", rejected.toString(), "not-a-number"));
            Process process = CliProcess.launch(garden, command);
            try {
                assert CliProcess.finish(process) == 1 : "Malformed polling input must be a checked CLI rejection";
                assert !Files.exists(rejected) : "Argument guards must precede state-directory creation";
                String output = Files.readString(garden.directory.resolve("cli-output"));
                assert output.strip().equals("Tend: Invalid polling interval") : "Arguments must render once without a private cause or context report";
            } finally { CliProcess.stop(process); }
        }
    }
}
record CliMissingProvider() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden(); IncusMock mock = new IncusMock()) {
            Path rejected = garden.directory.resolve("rejected-state");
            // Limit observable modules so service binding cannot resolve Culpa automatically.
            var command = new ArrayList<>(List.of(CliProcess.java(), "--module-path", "out:lib/bin", "--limit-modules", "work.archaic.tend",
                    "-m", "work.archaic.tend/work.archaic.tend.Main"));
            command.addAll(List.of("once", garden.remote.toString(), mock.endpoint().toString(), "garden", "owner", rejected.toString()));
            Process process = CliProcess.launch(garden, command);
            try {
                assert CliProcess.finish(process) == 1 : "Absent logging provider must reject composition";
                assert !Files.exists(rejected) : "Provider selection must precede filesystem changes";
                assert Files.readString(garden.directory.resolve("cli-output")).strip().equals("Tend: Expected exactly one Log provider") : "Composition failure must render without an active context";
            } finally { CliProcess.stop(process); }
        }
    }
}
final class CliProcess {
    static String java() { return Path.of(System.getProperty("java.home"), "bin/java").toString(); }
    static List<String> arguments(Garden garden, IncusMock mock, String mode) {
        return List.of(mode, garden.remote.toString(), mock.endpoint().toString(), "garden", "cli-controller", garden.state.toString(), "1");
    }
    static Process start(Garden garden, IncusMock mock, String mode, boolean debug) throws IOException {
        var command = new ArrayList<>(List.of(java(), "-Dtend.debug=" + debug, "@cmd/run"));
        command.addAll(arguments(garden, mock, mode));
        return launch(garden, command);
    }
    static Process launch(Garden garden, List<String> command) throws IOException {
        var configured = new ArrayList<>(command);
        configured.add(1, "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
        return new ProcessBuilder(configured).redirectOutput(garden.directory.resolve("cli-stdout").toFile())
                .redirectError(garden.directory.resolve("cli-output").toFile()).start();
    }
    static int invoke(Garden garden, IncusMock mock) throws Exception {
        Process process = start(garden, mock, "once", false);
        try { return finish(process); }
        finally { stop(process); }
    }
    static int finish(Process process) throws IOException, InterruptedException {
        if (!process.waitFor(20, TimeUnit.SECONDS)) throw new IOException("CLI fixture timed out");
        return process.exitValue();
    }
    static void awaitText(Process process, Path path, String text) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (readIfPresent(path).contains(text)) return;
            if (!process.isAlive()) throw new IOException("CLI exited before expected observation");
            Thread.sleep(25);
        }
        throw new IOException("CLI observation timed out");
    }
    private static String readIfPresent(Path path) throws IOException {
        try { return Files.readString(path); }
        catch (NoSuchFileException absent) { return ""; }
    }
    static void stop(Process process) throws InterruptedException {
        if (!process.isAlive()) return;
        process.destroyForcibly();
        process.waitFor();
    }
}

record CliPrivateLaunchFile() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden(); IncusMock mock = new IncusMock()) {
            String revision = garden.commit(Garden.xml(), "private launcher");
            Path launch = garden.directory.resolve("launch.args");
            Files.createFile(launch, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            Files.writeString(launch, "-Djavax.net.ssl.keyStorePassword=synthetic-private-launcher\n" + Files.readString(Path.of("cmd/run")) + "\n" + String.join("\n", CliProcess.arguments(garden, mock, "once")) + "\n");
            Process process = CliProcess.launch(garden, List.of(CliProcess.java(), "@" + launch));
            try {
                assert CliProcess.finish(process) == 0 : "A single complete private JDK argument file must expand launcher options and Tend arguments before the main target boundary";
                assert Files.readString(garden.state.resolve("last-success")).strip().equals(revision) : "The private launcher must perform actual reconciliation";
                assert !Files.readString(garden.directory.resolve("cli-output")).contains("synthetic-private-launcher") : "Private launcher properties must not reach logs";
            } finally { CliProcess.stop(process); }
        }
    }
}
