package work.archaic.tend.test;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import work.archaic.tend.Reconciler;
import work.archaic.tend.git.GitRepository;
import work.archaic.tend.incus.IncusClient;
import work.archaic.tend.secrets.SecretStore;
import work.archaic.tend.state.*;

/** Isolated real Git and loopback HTTP adapters; assertions belong to individual cases. */
final class DeploymentFixture implements AutoCloseable {
    final Garden garden;
    final IncusMock mock;
    final IncusClient client;
    final StateReader reader = new StateReader(Path.of("schema/tend.xsd"));
    final SecretStore store;
    final Reconciler engine;
    final DesiredState desired;
    DeploymentFixture() throws Exception { this(Duration.ofSeconds(5)); }
    DeploymentFixture(Duration timeout) throws Exception {
        garden = new Garden(); mock = new IncusMock();
        client = new IncusClient(mock.endpoint(), "garden", HttpClient.newHttpClient(), timeout);
        store = new SecretStore(garden.state.resolve("secrets"));
        engine = new Reconciler(client, store, "garden", "test-controller");
        garden.commit(Garden.xml(), "version=one\n");
        desired = reader.read(garden.git.fetchMain(), "incus.xml");
    }
    void deploy() throws Exception { engine.reconcile(desired); }
    DesiredState revision(String xml, String contents) throws Exception {
        garden.commit(xml, contents);
        return reader.read(garden.git.fetchMain(), "incus.xml");
    }
    public void close() throws Exception {
        try { client.close(); } finally { try { mock.close(); } finally { garden.close(); } }
    }
}
