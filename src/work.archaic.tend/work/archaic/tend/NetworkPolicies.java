package work.archaic.tend;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import work.archaic.tend.incus.IncusClient;
import work.archaic.tend.state.ResourceCompiler.Acl;

/** Applies NIC-scoped OVN egress policies without changing shared network ACLs. */
final class NetworkPolicies {
    private final IncusClient incus;
    private final ResourceSupport resources;
    NetworkPolicies(IncusClient incus, ResourceSupport resources) { this.incus = incus; this.resources = resources; }
    void preflight(List<Acl> acls) throws IOException, InterruptedException {
        for (var acl : acls) {
            resources.checkOwner(incus.get(path(acl.name())));
            var network = incus.get("/1.0/networks/" + IncusClient.encode(acl.network()));
            if (network == null || !"ovn".equals(ResourceSupport.value(network.value(), "type")))
                throw new ReconciliationException("Egress requires an existing managed OVN network");
            var config = network.value().getAsJsonObject("config");
            if (config != null && !ResourceSupport.value(config, "security.acls").isBlank())
                throw new ReconciliationException("Shared network ACLs cannot be combined with Tend egress in this slice");
        }
    }
    void reconcile(List<Acl> acls) throws IOException, InterruptedException {
        for (var acl : acls) reconcile(acl);
    }
    private void reconcile(Acl acl) throws IOException, InterruptedException {
        var current = incus.get(path(acl.name()));
        JsonObject body = new JsonObject();
        body.add("config", resources.managedConfig(current, Map.of()));
        body.addProperty("description", "Tend egress");
        body.add("ingress", new JsonArray()); body.add("egress", acl.egress());
        if (current == null) {
            body.addProperty("name", acl.name());
            incus.mutate("POST", "/1.0/network-acls", body, null);
            return;
        }
        if (body.entrySet().stream().allMatch(e -> e.getValue().equals(current.value().get(e.getKey())))) return;
        incus.mutate("PUT", path(acl.name()), body, current.etag());
    }
    private static String path(String name) { return "/1.0/network-acls/" + IncusClient.encode(name); }
}
