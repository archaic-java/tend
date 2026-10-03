package work.archaic.tend;

import java.io.IOException;
import java.util.*;
import work.archaic.tend.incus.IncusClient;
import work.archaic.tend.secrets.SecretStore;
import work.archaic.tend.state.DesiredState;
import work.archaic.tend.state.ResourceCompiler;
import work.archaic.tend.state.ResourceCompiler.Deployment;
import static work.archaic.tend.ResourceSupport.*;

/** One serialized convergence pass; all ownership and policy preflights precede mutation. */
public final class Reconciler {
    private final IncusClient incus;
    private final SecretStore secrets;
    private final String project;
    private final ResourceSupport resources;
    private final Instances instances;
    private final Volumes volumes;
    private final NetworkPolicies policies;
    public Reconciler(IncusClient incus, SecretStore secrets, String project, String owner) {
        if (owner.isBlank()) throw new IllegalArgumentException("Owner must be stable and nonempty");
        this.incus = incus; this.secrets = secrets; this.project = project;
        resources = new ResourceSupport(owner);
        instances = new Instances(incus, resources);
        volumes = new Volumes(incus, resources, instances);
        policies = new NetworkPolicies(incus, resources);
    }
    public synchronized void reconcile(DesiredState state) throws IOException, InterruptedException {
        var desired = new ResourceCompiler().compile(state);
        preflight(desired);
        Map<String, byte[]> values = secrets.resolve(desired.secrets());
        policies.reconcile(desired.acls());
        var digests = volumes.reconcile(desired, values);
        // Authorization must activate before gateway routes can start referring to it.
        if (state.gateway() != null) {
            String name = state.gateway().authorizationInstance();
            var authorization = desired.instances().stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
            instances.reconcile(authorization, digests);
        }
        for (var instance : desired.instances()) {
            if (state.gateway() != null && instance.name().equals(state.gateway().authorizationInstance())) continue;
            instances.reconcile(instance, digests);
        }
    }
    private void preflight(Deployment desired) throws IOException, InterruptedException {
        if (!desired.project().equals(project)) throw new ReconciliationException("Desired project differs from controller scope");
        for (var volume : desired.volumes()) resources.checkOwner(incus.get(IncusClient.volumePath(volume.pool(), volume.name())));
        for (var instance : desired.instances()) preflightInstance(instance);
        policies.preflight(desired.acls());
    }
    private void preflightInstance(DesiredState.Instance instance) throws IOException, InterruptedException {
        var observed = incus.get(instancePath(instance.name()));
        resources.checkOwner(observed);
        if (observed == null) return;
        if (!instance.type().equals(observed.value().get("type").getAsString()) ||
                !instance.fingerprint().equals(value(observed.value().getAsJsonObject("config"), IMAGE)))
            throw new ReconciliationException("Image/type replacement is not implemented in this slice");
    }
}
