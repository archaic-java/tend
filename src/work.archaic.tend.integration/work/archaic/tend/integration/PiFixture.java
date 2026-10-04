package work.archaic.tend.integration;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;

/** Homelab's pinned installer and systemd unit, delivered by ordinary Incus cloud-init. */
final class PiFixture {
    static final String INSTANCE = "tend-ci-pi";
    static final String RELEASE = "build-de8c7c683751d0bd91d6e92a8b5f9ea8497f39de";
    static final String ARCHIVE_SHA256 = "7b004aaf4be27d0b1e8ad0860c3897fe66d701f28c67e5982f01417744f0756b";
    private final IncusCommands incus;
    PiFixture(IncusCommands incus) { this.incus = incus; }

    String xml() throws IOException {
        String fingerprint = Files.readString(Path.of("out/incus-smoke/pi-fingerprint.txt")).strip();
        if (!fingerprint.matches("[a-f0-9]{64}")) throw new IOException("Expected resolved Debian cloud VM fingerprint");
        return """
                <incus project="default">
                  <volume pool="tend-ci-pool" name="tend-ci-pi-agent"><config>
                    <entry key="initial.uid" value="1000"/><entry key="initial.gid" value="1000"/>
                    <entry key="initial.mode" value="0700"/>
                  </config></volume>
                  <volume pool="tend-ci-pool" name="tend-ci-pi-workspace"><config>
                    <entry key="initial.uid" value="1000"/><entry key="initial.gid" value="1000"/>
                    <entry key="initial.mode" value="0700"/>
                  </config></volume>
                  <instance name="tend-ci-pi" type="virtual-machine" fingerprint="%s"><config>
                    <entry key="limits.cpu" value="2"/><entry key="limits.memory" value="2GiB"/>
                    <entry key="boot.autostart" value="true"/>
                    <entry key="cloud-init.user-data" value="%s"/>
                  </config>
                    <device name="root" type="disk"><config>
                      <entry key="path" value="/"/><entry key="pool" value="tend-ci-pool"/>
                      <entry key="size" value="10GiB"/>
                    </config></device>
                    <device name="eth0" type="nic"><config>
                      <entry key="network" value="tend-ci-pi-net"/><entry key="ipv4.address" value="10.78.0.10"/>
                    </config></device>
                    <device name="agent" type="disk"><config>
                      <entry key="path" value="/var/lib/pi"/><entry key="pool" value="tend-ci-pool"/>
                      <entry key="source" value="tend-ci-pi-agent"/>
                    </config></device>
                    <device name="workspace" type="disk"><config>
                      <entry key="path" value="/workspace"/><entry key="pool" value="tend-ci-pool"/>
                      <entry key="source" value="tend-ci-pi-workspace"/>
                    </config></device>
                  </instance>
                </incus>
                """.formatted(fingerprint, attribute(cloudInit()));
    }
    private String cloudInit() throws IOException {
        var config = new JsonObject();
        config.addProperty("package_update", true); config.addProperty("package_upgrade", true);
        var packages = new JsonArray();
        for (String name : new String[]{"ca-certificates", "curl", "xz-utils", "ripgrep", "fd-find", "git", "unattended-upgrades"}) packages.add(name);
        config.add("packages", packages);
        var user = new JsonObject(); user.addProperty("name", "pi"); user.addProperty("uid", 1000);
        user.addProperty("lock_passwd", true); user.addProperty("shell", "/usr/sbin/nologin"); user.addProperty("no_create_home", true);
        var users = new JsonArray(); users.add(user); config.add("users", users);
        var files = new JsonArray();
        String installer = Files.readString(Path.of("scripts/incus-smoke/pi/install.sh"))
                .replace("${release_ref}", RELEASE).replace("${archive_sha256}", ARCHIVE_SHA256);
        file(files, "/opt/homelab-pi/install.sh", installer, "0444");
        file(files, "/etc/systemd/system/homelab-pi.service", Files.readString(Path.of("scripts/incus-smoke/pi/pi.service")), "0444");
        // Initial-install smoke needs a declared model, but sends no model requests.
        file(files, "/etc/homelab-pi/models.json", """
                {"providers":{"homelab":{"baseUrl":"http://127.0.0.1:9/v1","api":"openai-completions",
                "apiKey":"local-no-secret","models":[{"id":"smoke","name":"smoke","reasoning":true,
                "input":["text"],"contextWindow":49152,"maxTokens":8192,
                "cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0}}]}}}
                """, "0444");
        file(files, "/etc/homelab-pi/service.env", "PI_PROVIDER=homelab\nPI_WEB_UI_ORIGIN=https://pi.garden.internal\nPI_MODEL_ID=smoke\n", "0444");
        file(files, "/etc/apt/apt.conf.d/20auto-upgrades", "APT::Periodic::Update-Package-Lists \"1\";\nAPT::Periodic::Unattended-Upgrade \"1\";\n", "0644");
        config.add("write_files", files);
        var command = new JsonArray(); command.add("/bin/sh"); command.add("/opt/homelab-pi/install.sh");
        var commands = new JsonArray(); commands.add(command); config.add("runcmd", commands);
        // JSON is valid YAML; Incus passes these bytes directly to cloud-init.
        return "#cloud-config\n" + new GsonBuilder().setPrettyPrinting().create().toJson(config) + "\n";
    }
    private static void file(JsonArray files, String path, String content, String mode) {
        var file = new JsonObject(); file.addProperty("path", path); file.addProperty("content", content);
        file.addProperty("owner", "root:root"); file.addProperty("permissions", mode); files.add(file);
    }
    private static String attribute(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
                .replace("\r", "&#13;").replace("\n", "&#10;").replace("\t", "&#9;");
    }
    IncusCommands.Result agent() throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        IncusCommands.Result result;
        do {
            result = incus.run(Duration.ofSeconds(10), "exec", INSTANCE, "--", "true");
            if (result.status() == 0) return result;
            Thread.sleep(500);
        } while (System.nanoTime() < deadline);
        return result;
    }
    IncusCommands.Result installed() throws IOException, InterruptedException {
        return incus.run(Duration.ofMinutes(8), "exec", INSTANCE, "--", "cloud-init", "status", "--wait", "--long");
    }
    IncusCommands.Result health() throws IOException, InterruptedException {
        return incus.command(Duration.ofSeconds(15), java.util.List.of("curl", "--fail", "--silent", "--show-error",
                "--connect-timeout", "3", "--max-time", "10", "http://10.78.0.10:3001/api/health"));
    }
    IncusCommands.Result ready() throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        IncusCommands.Result result;
        do {
            result = health();
            if (result.status() == 0) return result;
            Thread.sleep(500);
        } while (System.nanoTime() < deadline);
        return result;
    }
}
