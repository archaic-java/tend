package work.archaic.tend;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import work.archaic.service.logging.v02.*;
import work.archaic.tend.git.GitRepository;
import work.archaic.tend.incus.IncusClient;
import work.archaic.tend.secrets.SecretStore;
import work.archaic.tend.state.StateReader;

/** CLI composition root; bootstrap credentials use standard JSSE key/trust stores. */
public final class Main {
    private Main() {}
    public static void main(String... args) throws ControllerFailure, IOException, InterruptedException {
        switch (args.length == 0 ? "" : args[0]) {
            case "--help" -> help();
            case "once", "watch" -> run(Arguments.parse(args));
            default -> throw new ControllerFailure("Run --help for arguments", null);
        }
    }
    private static void help() {
        System.out.println("Tend: once|watch REPOSITORY INCUS_URL PROJECT OWNER STATE_DIRECTORY [POLL_SECONDS]");
        System.out.println("Reads incus.xml from main. TLS: JSSE keyStore/trustStore properties; loopback HTTP is for offline tests.");
    }
    private static void run(Arguments args) throws IOException, ControllerFailure, InterruptedException {
        Files.createDirectories(args.directory());
        try (var channel = FileChannel.open(args.directory().resolve("controller.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new ControllerFailure("Another Tend process holds this state directory", null);
            runLocked(args);
        } catch (java.nio.channels.OverlappingFileLockException e) {
            throw new ControllerFailure("Another Tend process holds this state directory", e);
        }
    }
    private static void runLocked(Arguments args) throws IOException, ControllerFailure, InterruptedException {
        var diagnostics = one(Diagnostics.class);
        var log = one(Log.class);
        var git = new GitRepository(args.directory().resolve("repository.git"), args.repository());
        var reader = new StateReader(Path.of("schema/tend.xsd"));
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        try (var incus = new IncusClient(args.endpoint(), args.project(), http, Duration.ofSeconds(45))) {
            var engine = new Reconciler(incus, new SecretStore(args.directory().resolve("secrets")), args.project(), args.owner());
            var controller = new Controller(git, reader, engine, args.directory(), diagnostics, log);
            controller.run(args.watch(), args.polling());
        }
    }
    private static <T> T one(Class<T> contract) {
        List<T> providers = ServiceLoader.load(contract).stream().map(ServiceLoader.Provider::get).toList();
        if (providers.size() != 1) throw new IllegalStateException("Expected exactly one " + contract.getSimpleName() + " provider");
        return providers.getFirst();
    }
}
record Arguments(boolean watch, String repository, URI endpoint, String project, String owner, Path directory, Duration polling) {
    static Arguments parse(String[] args) throws ControllerFailure {
        if (args.length < 6 || args.length > 7) throw new ControllerFailure("Run --help for arguments", null);
        try {
            URI endpoint = URI.create(args[2]);
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(Objects.toString(endpoint.getHost(), ""));
            if (!"https".equals(endpoint.getScheme()) && !("http".equals(endpoint.getScheme()) && loopback))
                throw new ControllerFailure("Remote Incus requires HTTPS", null);
            if (endpoint.getHost() == null || endpoint.getRawQuery() != null || endpoint.getRawUserInfo() != null ||
                    endpoint.getRawFragment() != null || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/")))
                throw new ControllerFailure("Incus endpoint must be an HTTP(S) origin", null);
            if (args[1].isBlank() || args[1].startsWith("-")) throw new ControllerFailure("Invalid Git remote", null);
            if (!args[3].matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}") || args[4].isBlank())
                throw new ControllerFailure("Project and owner must be valid and nonempty", null);
            long seconds = args.length == 7 ? Long.parseLong(args[6]) : 30;
            if (seconds < 1 || seconds > 3600) throw new ControllerFailure("Polling interval must be 1..3600 seconds", null);
            return new Arguments(args[0].equals("watch"), args[1], endpoint, args[3], args[4], Path.of(args[5]), Duration.ofSeconds(seconds));
        } catch (IllegalArgumentException e) { throw new ControllerFailure("Invalid command-line argument", e); }
    }
}
