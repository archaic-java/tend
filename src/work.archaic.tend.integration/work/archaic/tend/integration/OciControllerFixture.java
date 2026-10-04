package work.archaic.tend.integration;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Operator bootstrap and private observations; never launches Tend on the runner. */
final class OciControllerFixture {
    static final String PROJECT = "tend-ci-oci";
    static final String CONTROLLER = "tend-ci-watch";
    static final String APPLICATION = "tend-ci-oci-app";
    private static final String STATE = "tend-ci-watch-state";
    private static final String BOOTSTRAP = "tend-ci-watch-bootstrap";
    private static final String GIT = "tend-ci-watch-git";
    private final IncusCommands commands;
    private final RealGarden garden;
    private final Path privateDirectory;
    private final String fingerprint;
    private int privateSequence;

    OciControllerFixture(IncusCommands commands, RealGarden garden) throws IOException {
        this.commands = commands; this.garden = garden;
        privateDirectory = Path.of(Files.readString(Path.of("out/incus-smoke/controller-directory.txt")).strip());
        fingerprint = Files.readString(Path.of("out/incus-smoke/controller-fingerprint.txt")).strip();
        if (!fingerprint.matches("[a-f0-9]{64}")) throw new IOException("Expected cached controller OCI fingerprint");
    }

    String publish(String version) throws IOException, InterruptedException {
        return garden.commitXml(xml(version), "OCI watch desired state " + version);
    }

    void bootstrap(String mode) throws IOException, InterruptedException {
        var result = commands.command(Duration.ofMinutes(3), List.of("sudo", "-n", "bash",
                "scripts/bootstrap/controller", mode, "local", PROJECT, "tend-ci-pool",
                "tend-ci-ctl", fingerprint, CONTROLLER, STATE, BOOTSTRAP, PROJECT,
                privateDirectory.toString()));
        if (result.status() != 0) throw new IOException("Operator OCI bootstrap failed; private details withheld");
    }

    void prepareGit() throws IOException, InterruptedException {
        require("storage", "volume", "create", "tend-ci-pool", GIT,
                "security.shifted=true", "initial.uid=1000", "initial.gid=1000", "initial.mode=0700",
                "user.tend.bootstrap=" + PROJECT);
        copyGit();
        attachGit();
    }

    void copyGit() throws IOException, InterruptedException {
        // Publish objects first, refs last: a polling fetch can only select a complete commit.
        require("storage", "volume", "file", "push", "--recursive", garden.remote().resolve("objects").toString(),
                "tend-ci-pool", GIT + "/remote.git/", "--create-dirs", "--uid=1000", "--gid=1000", "--mode=0700");
        require("storage", "volume", "file", "push", garden.remote().resolve("HEAD").toString(),
                "tend-ci-pool", GIT + "/remote.git/HEAD", "--create-dirs", "--uid=1000", "--gid=1000", "--mode=0600");
        require("storage", "volume", "file", "push", "--recursive", garden.remote().resolve("refs").toString(),
                "tend-ci-pool", GIT + "/remote.git/", "--uid=1000", "--gid=1000", "--mode=0700");
    }

    void attachGit() throws IOException, InterruptedException {
        require("config", "device", "add", CONTROLLER, "git", "disk", "pool=tend-ci-pool",
                "source=" + GIT, "path=/srv/tend-git", "readonly=true");
    }

    void detachGit() throws IOException, InterruptedException { require("config", "device", "remove", CONTROLLER, "git"); }
    void start() throws IOException, InterruptedException { require("start", CONTROLLER); }
    void stop() throws IOException, InterruptedException { require("stop", CONTROLLER, "--timeout=30"); }
    void deleteController() throws IOException, InterruptedException { require("delete", CONTROLLER); }

    void trust(boolean authorized) throws IOException, InterruptedException {
        if (!authorized) {
            commands.require("config", "trust", "remove", Files.readString(privateDirectory.resolve("client-fingerprint")).strip());
            return;
        }
        commands.require("config", "trust", "add-certificate", privateDirectory.resolve("client.crt").toString(),
                "--name=tend-ci-oci", "--restricted", "--projects=" + PROJECT);
    }

    void trustStore(boolean correct) throws IOException, InterruptedException {
        Path source = privateDirectory.resolve(correct ? "trust.p12" : "wrong-trust.p12");
        require("storage", "volume", "file", "push", source.toString(), "tend-ci-pool", BOOTSTRAP + "/trust.p12",
                "--uid=1000", "--gid=1000", "--mode=0400");
    }

    String lastSuccess() throws IOException, InterruptedException {
        var result = privateIncus("exec", CONTROLLER, "--project", PROJECT, "--", "cat", "/var/lib/tend/last-success");
        return result.status() == 0 ? result.output().strip() : "";
    }

    boolean converged(String revision) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        do {
            if (lastSuccess().equals(revision)) return true;
            Thread.sleep(300);
        } while (System.nanoTime() < deadline);
        return false;
    }

    String version() throws IOException, InterruptedException {
        return output("config", "get", APPLICATION, "environment.DEMO").strip();
    }
    void drift() throws IOException, InterruptedException { require("config", "set", APPLICATION, "environment.DEMO=drift"); }

    String applicationStarted() throws IOException, InterruptedException {
        String stat = output("exec", APPLICATION, "--", "cat", "/proc/1/stat");
        return stat.substring(stat.lastIndexOf(')') + 2).split("\\s+")[19];
    }

    String runtimeIdentity() throws IOException, InterruptedException {
        return output("exec", CONTROLLER, "--", "sh", "-c",
                "for comm in /proc/[0-9]*/comm; do " +
                "case $(cat \"$comm\" 2>/dev/null) in java) " +
                "awk '/^Uid:|^Gid:/ { print $2 }' \"${comm%comm}status\";; esac; done; " +
                "stat -c '%u:%g:%a' /var/lib/tend /etc/tend-bootstrap/java.args");
    }

    String ociMarker() throws IOException, InterruptedException {
        return output("config", "get", CONTROLLER, "volatile.container.oci").strip();
    }

    List<String> privateState() throws IOException, InterruptedException {
        return List.of(readPrivate(CONTROLLER, "/var/lib/tend/secrets/session"),
                readPrivate(CONTROLLER, "/var/lib/tend/secrets/signing"),
                readPrivate(APPLICATION, "/etc/tend-oci-session/value"),
                readPrivate(APPLICATION, "/etc/tend-oci-signing/value"));
    }

    private String readPrivate(String instance, String path) throws IOException, InterruptedException {
        var result = privateIncus("exec", instance, "--project", PROJECT, "--", "cat", path);
        if (result.status() != 0) throw new IOException("Cannot inspect private controller persistence");
        return result.output();
    }

    long failures() throws IOException, InterruptedException {
        var result = privateIncus("console", CONTROLLER, "--project", PROJECT, "--show-log");
        if (result.status() != 0) throw new IOException("Cannot inspect private controller console");
        return result.output().lines().filter(line -> line.contains("Reconciliation failed")).count();
    }

    boolean failedAfter(long previous) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        do {
            if (failures() > previous) return true;
            Thread.sleep(300);
        } while (System.nanoTime() < deadline);
        return false;
    }

    boolean secretsExcluded(List<String> secrets) throws IOException, InterruptedException {
        var console = privateIncus("console", CONTROLLER, "--project", PROJECT, "--show-log");
        if (console.status() != 0) throw new IOException("Cannot inspect private controller console");
        var values = new ArrayList<>(secrets);
        values.add(Files.readString(privateDirectory.resolve("store-password")).strip());
        var texts = new ArrayList<>(List.of(console.output()));
        try (var files = Files.walk(Path.of("out/incus-smoke/controller"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) texts.add(Files.readString(file));
        }
        for (String text : texts) if (containsSecret(text, values)) return false;
        return true;
    }

    private static boolean containsSecret(String text, List<String> values) {
        for (String value : values) if (text.contains(value.strip())) return true;
        return false;
    }

    private IncusCommands.Result privateIncus(String... arguments) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("sudo", "-n", "incus")); command.addAll(List.of(arguments));
        Path output = garden.directory.resolve("controller-private-" + (++privateSequence));
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(15, TimeUnit.SECONDS)) throw new IOException("Private controller observation timed out");
            return new IncusCommands.Result(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
        }
    }

    private void require(String... arguments) throws IOException, InterruptedException {
        String[] scoped = scoped(arguments);
        commands.require(scoped);
    }
    private String output(String... arguments) throws IOException, InterruptedException {
        var result = commands.run(Duration.ofSeconds(30), scoped(arguments));
        if (result.status() != 0) throw new IOException("OCI controller observation failed; see safe command evidence");
        return result.output();
    }
    private static String[] scoped(String[] arguments) {
        var result = new ArrayList<>(List.of("--project", PROJECT)); result.addAll(List.of(arguments));
        return result.toArray(String[]::new);
    }

    private String xml(String version) {
        return """
                <incus project="tend-ci-oci">
                  <secret name="session"/><secret name="signing" kind="rsa-3072"/>
                  <instance name="tend-ci-oci-app" fingerprint="%s">
                    <config><entry key="environment.DEMO" value="%s"/></config>
                    <device name="root" type="disk"><config>
                      <entry key="path" value="/"/><entry key="pool" value="tend-ci-pool"/>
                    </config></device>
                    <mount name="session" secret="session" pool="tend-ci-pool" path="/etc/tend-oci-session" uid="1000" gid="1000" mode="0400"/>
                    <mount name="signing" secret="signing" pool="tend-ci-pool" path="/etc/tend-oci-signing" uid="1000" gid="1000" mode="0400"/>
                  </instance>
                </incus>
                """.formatted(garden.fingerprint, version);
    }
}
