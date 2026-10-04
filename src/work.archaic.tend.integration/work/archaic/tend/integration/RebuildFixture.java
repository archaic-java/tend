package work.archaic.tend.integration;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Exact pinned garden recipe, explicit CPU/local-CA inputs and private observations. */
final class RebuildFixture {
    static final String PROJECT = "tend-ci-rebuild", ISSUER = "https://auth.compose.localhost";
    private final IncusCommands commands;
    private final RealGarden garden;
    private final PrivateCommands privateCommands;
    private final Path prepared;
    RebuildFixture(IncusCommands commands, RealGarden garden) {
        this.commands = commands; this.garden = garden; privateCommands = new PrivateCommands(garden.directory);
        prepared = garden.directory.resolve("prepared");
    }
    OciControllerFixture controller() throws IOException {
        Path directory = Path.of(Files.readString(Path.of("out/incus-smoke/rebuild-directory.txt")).strip());
        return new OciControllerFixture(commands, garden, PROJECT, directory);
    }
    String prepare() throws Exception {
        var site = new JsonObject();
        for (var entry : Map.of("project", PROJECT, "owner", PROJECT, "pool", "tend-ci-pool", "private_network", "tend-ci-full",
                "private_cidr", "10.81.0.0/24", "domain", "compose.localhost", "gpu_pci", "0000:03:00.0").entrySet()) site.addProperty(entry.getKey(), entry.getValue());
        var addresses = new JsonObject(); int address = 10;
        for (String name : List.of("caddy", "authelia", "grafana", "prometheus", "llama", "openwebui", "pi")) addresses.addProperty(name, "10.81.0." + address++);
        site.add("addresses", addresses);
        site.add("hosts", JsonParser.parseString("{\"auth\":\"auth.compose.localhost\",\"grafana\":\"grafana.compose.localhost\",\"ai\":\"ai.compose.localhost\",\"pi\":\"pi.compose.localhost\"}"));
        site.add("lan", JsonParser.parseString("{\"nictype\":\"bridged\",\"parent\":\"tend-ci-nlan\",\"hwaddr\":\"02:00:00:00:81:10\"}"));
        site.add("incus_metrics", JsonParser.parseString("{\"target\":\"10.81.0.1:8443\",\"server_name\":\"10.81.0.1\"}"));
        site.add("pi", JsonParser.parseString("{\"cpu\":2,\"memory\":\"2GiB\",\"root_size\":\"10GiB\"}"));
        site.add("ci", JsonParser.parseString("{\"controlled_llama\":true,\"local_ca\":true,\"nameserver\":\"10.81.0.1\"}"));
        var images = new JsonObject();
        for (String app : List.of("caddy", "authelia", "grafana", "prometheus", "llama", "openwebui", "pi")) {
            String input = switch (app) { case "caddy", "authelia" -> "native-" + app; case "llama" -> "openwebui"; default -> app; };
            var image = new JsonObject();
            image.addProperty("fingerprint", Files.readString(Path.of("out/incus-smoke/" + input + "-fingerprint.txt")).strip());
            String source = app.equals("pi") ? "images:debian/13/cloud" : Files.readString(Path.of("out/incus-smoke/" + input + "-oci-image.txt")).strip();
            if (!app.equals("pi")) source = (app.equals("llama") || app.equals("openwebui") ? "ghcr.io/" : "docker.io/") + source;
            image.addProperty("source", source); image.addProperty("platform", "linux/amd64"); images.add(app, image);
        }
        site.add("images", images);
        Path input = garden.directory.resolve("site.json"); Files.writeString(input, site.toString());
        var generated = commands.command(Duration.ofSeconds(30), List.of("python3", "../digital-garden/scripts/prepare.py", input.toString(), prepared.toString()));
        if (generated.status() != 0) throw new IOException("Reviewed garden recipe rejected CI inputs");
        String credentials = Files.readString(Path.of("out/incus-smoke/credentials-directory.txt")).strip();
        Files.copy(Path.of(credentials, "server.crt"), prepared.resolve("root.crt"));
        try (var files = Files.list(prepared)) {
            for (Path file : files.toList()) garden.source(file.getFileName().toString(), Files.readString(file));
        }
        Files.copy(prepared.resolve("provenance.json"), Path.of("out/incus-smoke/rebuild-provenance.json"));
        return garden.commitXml(Files.readString(prepared.resolve("incus.xml")), "Prepare exact reviewed garden with explicit disposable substitutions");
    }
    String trustActualGateway() throws Exception {
        String ca = output("exec", "caddy", "--", "cat", "/data/caddy/pki/authorities/local/root.crt");
        Files.writeString(garden.directory.resolve("root.crt"), ca); garden.source("root.crt", ca);
        return garden.commitXml(Files.readString(prepared.resolve("incus.xml")), "Activate actual disposable Caddy public CA for OIDC consumers");
    }
    String update() throws Exception {
        String value = Files.readString(prepared.resolve("authelia.json")).replace("\"warn\"", "\"error\"");
        garden.source("authelia.json", value);
        return garden.commitXml(Files.readString(prepared.resolve("incus.xml")), "Activate one affected application's public Git configuration");
    }
    void ready() throws Exception {
        // Agent and cloud-init are deliberately bounded operator/test gates, not a controller scheduler.
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (run(Duration.ofSeconds(10), "exec", "pi", "--", "true").status() != 0) {
            if (System.nanoTime() >= deadline) throw new IOException("Composed Pi agent unavailable");
            Thread.sleep(500);
        }
        var cloud = run(Duration.ofMinutes(8), "exec", "pi", "--", "cloud-init", "status", "--wait", "--long");
        if (cloud.status() != 0 || !cloud.output().contains("status: done")) throw new IOException("Composed Pi cloud-init failed");
        for (String address : List.of("10.81.0.11:9091/api/health", "10.81.0.12:3000/api/health", "10.81.0.13:9090/-/ready",
                "10.81.0.14:8080/health", "10.81.0.15:8080/health", "10.81.0.16:3001/api/health")) {
            deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            while (commands.command(Duration.ofSeconds(12), List.of("curl", "--fail", "--silent", "--max-time", "10", "http://" + address)).status() != 0) {
                if (System.nanoTime() >= deadline) throw new IOException("Composed workload readiness failed");
                Thread.sleep(500);
            }
        }
    }
    String started(String instance) throws Exception {
        String stat = output("exec", instance, "--", "cat", "/proc/1/stat");
        return stat.substring(stat.lastIndexOf(')') + 2).split("\\s+")[19];
    }
    String secret() throws Exception { return privateCommands.incus("exec", OciControllerFixture.CONTROLLER, "--project", PROJECT, "--", "cat", "/var/lib/tend/secrets/webui-client"); }
    JsonObject targets() throws Exception {
        var result = commands.command(Duration.ofSeconds(12), List.of("curl", "--fail", "--silent", "--max-time", "10", "http://10.81.0.13:9090/api/v1/targets"));
        if (result.status() != 0) throw new IOException("Composed targets unavailable");
        return JsonParser.parseString(result.output()).getAsJsonObject();
    }
    JsonObject modelState() throws Exception {
        var result = commands.command(Duration.ofSeconds(12), List.of("curl", "--fail", "--silent", "--max-time", "10", "http://10.81.0.14:8080/fixture"));
        if (result.status() != 0) throw new IOException("Controlled composed model unavailable");
        return JsonParser.parseString(result.output()).getAsJsonObject();
    }
    private IncusCommands.Result run(Duration timeout, String... args) throws Exception {
        var all = new ArrayList<>(List.of("--project", PROJECT)); all.addAll(List.of(args));
        return commands.run(timeout, all.toArray(String[]::new));
    }
    private String output(String... args) throws Exception {
        var result = run(Duration.ofSeconds(30), args);
        if (result.status() != 0) throw new IOException("Composed observation failed");
        return result.output();
    }
    boolean evidenceExcluded(Set<String> browserValues, String secret) throws Exception {
        var values = new HashSet<>(browserValues); values.add(secret.strip());
        String console = privateCommands.incus("console", "openwebui", "--project", PROJECT, "--show-log");
        for (String value : values) if (value.length() >= 16 && console.contains(value)) return false;
        try (var paths = Files.walk(Path.of("out/incus-smoke"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String text = new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
                for (String value : values) if (value.length() >= 16 && text.contains(value)) return false;
            }
        }
        return true;
    }
}
