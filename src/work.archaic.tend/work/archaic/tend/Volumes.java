package work.archaic.tend;

import com.google.gson.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import work.archaic.tend.incus.IncusClient;
import work.archaic.tend.state.DesiredState;
import work.archaic.tend.state.ResourceCompiler.Deployment;
import static work.archaic.tend.ResourceSupport.*;

/** Converges custom volumes and invalidates consumers before writing mounted files. */
final class Volumes {
    private final IncusClient incus;
    private final ResourceSupport resources;
    private final Instances activation;
    Volumes(IncusClient incus, ResourceSupport resources, Instances activation) {
        this.incus = incus; this.resources = resources; this.activation = activation;
    }
    Map<String, String> reconcile(Deployment desired, Map<String, byte[]> values) throws IOException, InterruptedException {
        Map<String, String> digests = new HashMap<>();
        for (var volume : desired.volumes()) {
            reconcileVolume(volume);
            String digest = reconcileFiles(volume, desired.instances(), values);
            digests.put(volume.pool() + "/" + volume.name(), digest);
        }
        return digests;
    }
    private void reconcileVolume(DesiredState.Volume volume) throws IOException, InterruptedException {
        String path = IncusClient.volumePath(volume.pool(), volume.name());
        var current = incus.get(path);
        JsonObject config = resources.managedConfig(current, volume.config());
        if (current == null) {
            JsonObject body = new JsonObject();
            body.addProperty("name", volume.name()); body.addProperty("type", "custom");
            body.addProperty("content_type", "filesystem"); body.add("config", config);
            incus.mutate("POST", "/1.0/storage-pools/" + IncusClient.encode(volume.pool()) + "/volumes/custom", body, null);
        } else if (!config.equals(current.value().getAsJsonObject("config"))) {
            JsonObject body = new JsonObject(); body.add("config", config);
            body.addProperty("description", value(current.value(), "description"));
            incus.mutate("PUT", path, body, current.etag());
        }
    }
    private String reconcileFiles(DesiredState.Volume volume, List<DesiredState.Instance> instances, Map<String, byte[]> values) throws IOException, InterruptedException {
        String path = IncusClient.volumePath(volume.pool(), volume.name());
        MessageDigest digest = digest();
        for (var file : volume.files().stream().sorted(Comparator.comparing(DesiredState.File::path)).toList()) {
            byte[] content = file.secret().isBlank() ? file.content() : values.get(file.secret());
            var old = incus.file(path, file.path());
            boolean metadataDrift = old != null && (!old.uid().equals(Integer.toString(file.uid())) ||
                    !old.gid().equals(Integer.toString(file.gid())) || !old.mode().equals(file.mode()) || !old.type().equals("file"));
            if (old == null || !Arrays.equals(content, old.content()) || metadataDrift) {
                markConsumersPending(volume, instances);
                // Incus overwrite preserves existing metadata; recreate to repair ownership or mode.
                if (metadataDrift) incus.deleteFile(path, file.path());
                incus.writeFile(path, file.path(), content, file.uid(), file.gid(), file.mode());
            }
            update(digest, file.path()); update(digest, Integer.toString(file.uid()));
            update(digest, Integer.toString(file.gid())); update(digest, file.mode());
            digest.update(digest().digest(content));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private void markConsumersPending(DesiredState.Volume volume, List<DesiredState.Instance> instances) throws IOException, InterruptedException {
        for (var instance : instances) {
            boolean consumes = instance.devices().values().stream().anyMatch(d -> volume.pool().equals(d.get("pool")) && volume.name().equals(d.get("source")));
            if (consumes) activation.markPending(instancePath(instance.name()));
        }
    }
}
