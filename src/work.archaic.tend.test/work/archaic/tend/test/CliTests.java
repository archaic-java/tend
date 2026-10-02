package work.archaic.tend.test;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import work.archaic.service.test.v02.*;

public record CliTests() implements TestSuite {
    public void cases(Collection<TestCase> cases) {
        cases.add(new CliCase(false));
        cases.add(new CliCase(true));
    }
}
record CliCase(boolean invalidRevision) implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden(); IncusMock mock = new IncusMock()) {
            String revision = garden.commit(Garden.xml(), "cli");
            int status = invoke(garden, mock);
            assert status == 0 : "CLI composition must reconcile using real adapters and logging providers";
            Path success = garden.state.resolve("last-success");
            assert Files.readString(success).strip().equals(revision) : "CLI records success only for a complete revision";
            String output = Files.readString(garden.directory.resolve("cli-output"));
            String secret = Files.readString(garden.state.resolve("secrets/session"));
            assert !output.contains(secret) : "CLI output must not expose generated secret values";
            if (invalidRevision) {
                int mutations = mock.mutations;
                garden.commit("<incus project=\"garden\"><unknown/></incus>", "invalid");
                assert invoke(garden, mock) != 0 : "Invalid XML must fail the CLI pass";
                assert mock.mutations == mutations : "Invalid revision cannot mutate deployed resources";
                assert Files.readString(success).strip().equals(revision) : "Failed revision must preserve the last successful status";
            }
        }
    }
    private int invoke(Garden garden, IncusMock mock) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "@cmd/run", "once", garden.remote.toString(), mock.endpoint().toString(), "garden", "cli-controller", garden.state.toString())
                .redirectErrorStream(true).redirectOutput(garden.directory.resolve("cli-output").toFile()).start();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) throw new java.io.IOException("CLI fixture timed out");
            return process.exitValue();
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); } }
    }
}
