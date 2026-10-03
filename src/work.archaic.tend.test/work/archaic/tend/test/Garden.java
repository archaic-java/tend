package work.archaic.tend.test;

import java.nio.file.*;
import java.util.*;
import work.archaic.tend.git.GitRepository;

/** Real disposable bare remote and authoring repository; no public Git service needed. */
final class Garden implements AutoCloseable {
    final Path directory = Files.createTempDirectory("tend-case-");
    final Path remote = directory.resolve("remote.git");
    final Path author = directory.resolve("author");
    final Path state = directory.resolve("controller");
    final GitRepository git = new GitRepository(state.resolve("repository.git"), remote.toString());
    static final String IMAGE = "a".repeat(64);
    static final String INSTANCE = "/1.0/instances/demo";
    static final String VOLUME = "/1.0/storage-pools/pool/volumes/custom/demo-data";
    Garden() throws Exception {
        Files.createDirectories(remote); Files.createDirectories(author); Files.createDirectories(state);
        GitRepository.command(remote, "init", "--bare", "--initial-branch=main");
        GitRepository.command(author, "init", "--initial-branch=main");
        GitRepository.command(author, "config", "user.name", "Tend test");
        GitRepository.command(author, "config", "user.email", "tend-test@example.invalid");
        GitRepository.command(author, "remote", "add", "origin", remote.toString());
    }
    String commit(String xml, String config) throws Exception {
        Files.writeString(author.resolve("incus.xml"), xml);
        Files.writeString(author.resolve("service.conf"), config);
        GitRepository.command(author, "add", ".");
        GitRepository.command(author, "commit", "--allow-empty", "-m", "Desired state");
        GitRepository.command(author, "push", "--force", "origin", "main");
        return git.fetchMain().commit();
    }
    static String xml() {
        return """
                <incus project="garden">
                  <secret name="session" bytes="32"/>
                  <volume pool="pool" name="demo-data">
                    <config><entry key="initial.mode" value="0700"/><entry key="security.shifted" value="true"/></config>
                    <file path="/service.conf" source="service.conf" uid="1000" gid="1000" mode="0644"/>
                    <file path="/session" secret="session" uid="1000" gid="1000" mode="0400"/>
                  </volume>
                  <instance name="demo" fingerprint="%s">
                    <config><entry key="environment.DEMO" value="one"/></config>
                    <device name="root" type="disk"><config><entry key="path" value="/"/><entry key="pool" value="pool"/></config></device>
                    <device name="data" type="disk"><config><entry key="path" value="/data"/><entry key="pool" value="pool"/><entry key="source" value="demo-data"/></config></device>
                  </instance>
                </incus>
                """.formatted(IMAGE);
    }
    @Override public void close() throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
