package work.archaic.tend.integration;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;

/** Controlled CPU protocol only: no llama binary, weights, ROCm or GPU claim. */
final class LlamaFixture {
    static final String INSTANCE = "tend-ci-llama";
    private final IncusCommands incus;
    private final RealGarden garden;
    LlamaFixture(IncusCommands incus, RealGarden garden) { this.incus = incus; this.garden = garden; }
    String xml(String monitoring) throws IOException {
        String fingerprint = Files.readString(Path.of("out/incus-smoke/openwebui-fingerprint.txt")).strip();
        if (!fingerprint.matches("[a-f0-9]{64}")) throw new IOException("Expected cached Python OCI runtime");
        garden.source("models.ini", Files.readString(Path.of("examples/llama/models.ini")));
        garden.source("llama-protocol.py", Files.readString(Path.of("scripts/incus-smoke/llama-protocol.py")));
        String production = Files.readString(Path.of("examples/llama/incus.xml"));
        String fragment = production.substring(production.indexOf("  <configuration"), production.lastIndexOf("</incus>"))
                .replace("a".repeat(64), fingerprint).replace("name=\"llama\"", "name=\"" + INSTANCE + "\"")
                .replace("value=\"pool\"", "value=\"tend-ci-pool\"").replace("pool=\"pool\"", "pool=\"tend-ci-pool\"")
                .replace("value=\"private\"", "value=\"tend-ci-ctl\"").replace("10.20.0.30", "10.79.0.30")
                .replace("<entry key=\"oci.uid\"", "<entry key=\"oci.entrypoint\" value=\"python3 /etc/llama/llama-protocol.py\"/><entry key=\"oci.uid\"")
                .replace("</configuration>", "<file path=\"/llama-protocol.py\" source=\"llama-protocol.py\"/></configuration>");
        // Exact device removal is a deliberate reviewed CPU-only substitution.
        fragment = fragment.replaceAll("(?s)  <device name=\"(?:gpu|kfd)\".*?</device>\\n", "");
        return FixtureXml.ordered(monitoring.replace("</incus>", fragment + "</incus>"));
    }
    IncusCommands.Result request(String method, String path, String body) throws IOException, InterruptedException {
        return incus.run(Duration.ofSeconds(12), "exec", NativeIngressFixture.CLIENT, "--", "curl", "--silent", "--show-error",
                "--noproxy", "*", "--max-time", "8", "--request", method, "--header", "Content-Type: application/json",
                "--data", body, "http://10.79.0.30:8080" + path);
    }
    JsonObject get(String path) throws IOException, InterruptedException {
        var response = request("GET", path, "");
        if (response.status() != 0) throw new IOException("CPU protocol observation failed");
        return JsonParser.parseString(response.output()).getAsJsonObject();
    }
    void ready() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        do {
            if (request("GET", "/health", "").status() == 0) return;
            Thread.sleep(300);
        } while (System.nanoTime() < deadline);
        throw new IOException("Controlled CPU protocol fixture unavailable");
    }
}
