package work.archaic.tend;

import java.net.URI;
import java.net.http.HttpClient;
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
    public static void main(String... args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("Tend: once|watch REPOSITORY INCUS_URL PROJECT OWNER STATE_DIRECTORY [POLL_SECONDS]");
            System.out.println("Reads incus.xml from main. TLS: JSSE keyStore/trustStore properties; loopback HTTP is for offline tests.");
            return;
        }
        if (args.length < 6 || args.length > 7 || !Set.of("once", "watch").contains(args[0]))
            throw new IllegalArgumentException("Run --help for arguments");
        URI endpoint = URI.create(args[2]);
        if (!endpoint.getScheme().equals("https") && !(endpoint.getScheme().equals("http") &&
                Set.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost())))
            throw new IllegalArgumentException("Remote Incus requires HTTPS");
        long polling = args.length == 7 ? Long.parseLong(args[6]) : 30;
        if (polling < 1 || polling > 3600) throw new IllegalArgumentException("Polling interval must be 1..3600 seconds");
        Path directory = Path.of(args[5]);
        Files.createDirectories(directory);
        // The process lock protects the shared Git mirror, stable secrets and activation work.
        try (var channel = java.nio.channels.FileChannel.open(directory.resolve("controller.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Another Tend process holds this state directory");
            var diagnostics = one(Diagnostics.class);
            var log = one(Log.class);
            Goal reconcile = diagnostics.goal("tend.reconcile", log);
            var git = new GitRepository(directory.resolve("repository.git"), args[1]);
            var reader = new StateReader(Path.of("schema/tend.xsd"));
            try (var incus = new IncusClient(endpoint, args[3], HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Duration.ofSeconds(45))) {
                var engine = new Reconciler(incus, new SecretStore(directory.resolve("secrets")), args[3], args[4]);
                do {
                    try {
                        reconcile.run(() -> {
                            var revision = git.fetchMain();
                            var desired = reader.read(revision, "incus.xml");
                            diagnostics.note("Reconciling commit " + revision.commit());
                            engine.reconcile(desired);
                            Path temporary = directory.resolve("last-success.tmp");
                            Files.writeString(temporary, revision.commit() + "\n");
                            Files.move(temporary, directory.resolve("last-success"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                            log.write("Reconciled " + revision.commit());
                        });
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
                    catch (Exception e) { if (args[0].equals("once")) throw e; }
                    if (args[0].equals("watch")) Thread.sleep(Duration.ofSeconds(polling));
                } while (args[0].equals("watch"));
            }
        }
    }
    private static <T> T one(Class<T> contract) {
        List<T> providers = ServiceLoader.load(contract).stream().map(ServiceLoader.Provider::get).toList();
        if (providers.size() != 1) throw new IllegalStateException("Expected exactly one " + contract.getSimpleName() + " provider");
        return providers.getFirst();
    }
}
