package work.archaic.tend.integration;

import com.google.gson.*;
import java.time.Duration;
import java.util.*;
import work.archaic.service.test.v02.*;

/** Initial Pi installation only; VM replacement/update behavior is tracked separately. */
public record PiSmokeTests() implements TestSuite {
    public void cases(Collection<TestCase> cases) { cases.add(new PiCloudVmInstallsAndRetainsData()); }
}
record PiCloudVmInstallsAndRetainsData() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        var incus = new IncusCommands("pi");
        var pi = new PiFixture(incus);
        try (var garden = new RealGarden(incus)) {
            String revision = garden.commitXml(pi.xml(), "Install pinned Pi harness in Debian cloud VM");
            garden.reconcile();
            assert garden.lastSuccess().equals(revision) : "Tend must converge the actual XML main revision";
            var instance = incus.run(Duration.ofSeconds(15), "list", PiFixture.INSTANCE, "--format=json");
            assert instance.status() == 0 : "Pi VM configuration must be observable";
            var listed = JsonParser.parseString(instance.output()).getAsJsonArray();
            assert listed.size() == 1 : "The Pi fixture name must select exactly one instance";
            var observed = listed.get(0).getAsJsonObject();
            assert observed.get("type").getAsString().equals("virtual-machine") : "Pi must run as an actual VM";
            assert observed.getAsJsonArray("profiles").isEmpty() : "Pi must use only its explicit devices";
            assert observed.getAsJsonObject("config").get("user.tend.owner").getAsString().equals("tend-ci-controller") : "Tend must own the VM";
            assert pi.agent().status() == 0 : "The real Debian Incus agent must become available";
            var installed = pi.installed();
            assert installed.status() == 0 && installed.output().contains("status: done") : "Cloud-init must complete without installer errors";
            incus.require("exec", PiFixture.INSTANCE, "--", "systemctl", "is-active", "homelab-pi.service");
            var node = incus.run(Duration.ofSeconds(15), "exec", PiFixture.INSTANCE, "--", "/opt/node/bin/node", "--version");
            assert node.status() == 0 && node.output().strip().equals("v24.19.0") : "The checksum-pinned homelab Node release must be installed";
            var ready = pi.ready();
            assert ready.status() == 0 : "The actual Pi service must answer HTTP over the declared VM NIC";
            var health = JsonParser.parseString(ready.output()).getAsJsonObject();
            assert health.get("status").getAsString().equals("ok") && health.get("mode").getAsString().equals("webui") : "Health must identify the real web harness";
            var frontend = incus.command(Duration.ofSeconds(15), List.of("curl", "-fsS", "--max-time", "10", "http://10.78.0.10:3001/"));
            assert frontend.status() == 0 && frontend.output().toLowerCase(Locale.ROOT).contains("<!doctype html") : "Release must contain and serve built frontend assets";
            var unit = incus.run(Duration.ofSeconds(15), "exec", PiFixture.INSTANCE, "--", "systemctl", "show", "homelab-pi", "--property=User", "--value");
            assert unit.status() == 0 && unit.output().strip().equals("pi") : "Service must run as the unprivileged pi user";
            var log = incus.run(Duration.ofSeconds(15), "exec", PiFixture.INSTANCE, "--", "journalctl", "-u", "homelab-pi", "--no-pager");
            assert log.status() == 0 && log.output().contains("Pi ready; tools:") : "The real harness must complete startup and report its tool policy";
            assert log.output().lines().filter(line -> line.contains("Pi ready; tools:")).noneMatch(line -> line.matches(".*\\bbash\\b.*")) : "The startup tool list must exclude Bash";
            for (String path : List.of("/var/lib/pi", "/workspace")) {
                var mount = incus.run(Duration.ofSeconds(15), "exec", PiFixture.INSTANCE, "--", "findmnt", "--mountpoint", path, "--noheadings", "--output", "FSTYPE");
                assert mount.status() == 0 && Set.of("virtiofs", "9p").contains(mount.output().strip()) : "Data must be a real Incus filesystem-volume mount, not a root-disk directory";
                var ownership = incus.run(Duration.ofSeconds(15), "exec", PiFixture.INSTANCE, "--", "stat", "-c", "%u:%g:%a", path);
                assert ownership.status() == 0 && ownership.output().strip().equals("1000:1000:700") : "Data mount must have the declared private pi ownership";
                incus.require("exec", PiFixture.INSTANCE, "--", "runuser", "-u", "pi", "--", "/bin/sh", "-c", "printf 'pi-smoke-persistent\\n' > '" + path + "/tend-smoke.marker'");
            }
            incus.require("exec", PiFixture.INSTANCE, "--", "runuser", "-u", "pi", "--", "test", "!", "-w", "/etc/homelab-pi/models.json");
            String started = garden.started(PiFixture.INSTANCE);
            garden.reconcile();
            assert garden.started(PiFixture.INSTANCE).equals(started) : "An unchanged Git pass must not reboot the Pi VM";
            assert pi.health().status() == 0 : "No-op reconciliation must leave Pi serving requests";
            incus.require("restart", PiFixture.INSTANCE, "--timeout=60");
            assert pi.agent().status() == 0 : "VM agent must return after an explicit restart";
            assert pi.installed().status() == 0 : "Completed cloud-init must remain successful after restart";
            assert pi.ready().status() == 0 : "Systemd must start the harness again after restart";
            for (String path : List.of("/var/lib/pi", "/workspace")) {
                var marker = incus.run(Duration.ofSeconds(15), "exec", PiFixture.INSTANCE, "--", "runuser", "-u", "pi", "--", "cat", path + "/tend-smoke.marker");
                assert marker.status() == 0 && marker.output().equals("pi-smoke-persistent\n") : "Both persistent volumes must retain pi-user writes after restart";
            }
            assert garden.lastSuccess().equals(revision) : "Readiness and restart checks must not invent another desired revision";
            System.out.println("Pi smoke: real Debian VM installs pinned harness, serves frontend/health, excludes Bash, avoids no-op reboot and retains both data volumes across restart.");
        }
    }
}
