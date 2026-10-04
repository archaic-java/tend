package work.archaic.tend.state;

import java.util.*;

/** Complete, validated revision; file bytes are resolved before reconciliation starts. */
public record DesiredState(String project, List<Secret> secrets, List<Volume> volumes, List<Instance> instances,
                           List<Configuration> configurations, Gateway gateway, List<Ingress> ingresses, List<Egress> egresses) {
    public DesiredState {
        secrets = List.copyOf(secrets); volumes = List.copyOf(volumes); instances = List.copyOf(instances);
        configurations = List.copyOf(configurations); ingresses = List.copyOf(ingresses); egresses = List.copyOf(egresses);
    }
    public record Configuration(String name, List<File> files) {
        public Configuration { files = List.copyOf(files); }
    }
    public record Mount(String name, String configuration, String secret, String pool, String path, int uid, int gid, String mode) {}
    public record Gateway(String instance, String pool, String path, String authorizationInstance,
                          String authorizationDevice, int authorizationPort, String authorizationPath, int uid, int gid, int authorizationUid, int authorizationGid, String metricsDevice) {}
    public record Ingress(String name, String host, String instance, String device, int port, boolean publicAccess, String policy, List<String> groups) {
        public Ingress { groups = List.copyOf(groups); }
    }
    public record Rule(String address, String protocol, int port) {}
    public record Egress(String name, String instance, String device, List<Rule> rules) {
        public Egress { rules = List.copyOf(rules); }
    }
    public record Secret(String name, int bytes, String kind, String source) {
        public Secret(String name, int bytes) { this(name, bytes, "random", ""); }
    }
    public record File(String path, byte[] content, String secret, int uid, int gid, String mode) {
        public File { content = content == null ? null : content.clone(); }
        @Override public byte[] content() { return content == null ? null : content.clone(); }
    }
    public record Volume(String pool, String name, Map<String, String> config, List<File> files) {
        public Volume { config = Map.copyOf(config); files = List.copyOf(files); }
    }
    public record Instance(String name, String type, String fingerprint, boolean running,
                           Map<String, String> config, Map<String, Map<String, String>> devices, List<Mount> mounts) {
        public Instance {
            mounts = List.copyOf(mounts);
            config = Map.copyOf(config);
            Map<String, Map<String, String>> copy = new LinkedHashMap<>();
            devices.forEach((deviceName, properties) -> copy.put(deviceName, Map.copyOf(properties)));
            devices = Collections.unmodifiableMap(copy);
        }
    }
}
