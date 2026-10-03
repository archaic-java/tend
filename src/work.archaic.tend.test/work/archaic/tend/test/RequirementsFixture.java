package work.archaic.tend.test;

import com.google.gson.*;
import work.archaic.tend.state.DesiredState;

/** Application-level declarations using the same Incus-shaped instances as production. */
final class RequirementsFixture {
    static String mounts() {
        return Garden.xml()
                .replace("<volume pool=\"pool\"", "<configuration name=\"settings\"><file path=\"/service.conf\" source=\"service.conf\"/></configuration><volume pool=\"pool\"")
                .replace("<file path=\"/service.conf\" source=\"service.conf\" uid=\"1000\" gid=\"1000\" mode=\"0644\"/>", "")
                .replace("<file path=\"/session\" secret=\"session\" uid=\"1000\" gid=\"1000\" mode=\"0400\"/>", "")
                .replace("</instance>", """
                        <device name="eth0" type="nic"><config><entry key="network" value="garden-net"/><entry key="ipv4.address" value="10.20.0.10"/></config></device>
                        <mount name="settings" configuration="settings" pool="pool" path="/etc/demo" uid="1000" gid="1000" mode="0644"/>
                        <mount name="session" secret="session" pool="pool" path="/run/secrets/session" uid="1000" gid="1000" mode="0400"/>
                        </instance>
                        """);
    }
    static String ingress() {
        return mounts().replace("</incus>", """
                <instance name="caddy" fingerprint="%s">
                  <device name="root" type="disk"><config><entry key="path" value="/"/><entry key="pool" value="pool"/></config></device>
                </instance>
                <instance name="authelia" fingerprint="%s">
                  <config><entry key="environment.X_AUTHELIA_CONFIG" value="/config/configuration.yml,/etc/tend-authorization/access-control.json"/></config>
                  <device name="root" type="disk"><config><entry key="path" value="/"/><entry key="pool" value="pool"/></config></device>
                  <device name="eth0" type="nic"><config><entry key="network" value="garden-net"/><entry key="ipv4.address" value="10.20.0.11"/></config></device>
                </instance>
                <ingress-gateway instance="caddy" pool="pool" path="/etc/caddy" authorization-instance="authelia" authorization-device="eth0" authorization-path="/etc/tend-authorization"/>
                <ingress name="portal" host="auth.example.org" instance="authelia" device="eth0" port="9091"><public/></ingress>
                <ingress name="demo" host="demo.example.org" instance="demo" device="eth0" port="8080"><authorization><group name="gardeners"/><group name="admins"/></authorization></ingress>
                </incus>
                """.formatted(Garden.IMAGE, Garden.IMAGE));
    }
    static String egress() {
        return mounts().replace("</incus>", """
                <egress name="demo-outbound" instance="demo" device="eth0">
                  <allow address="10.20.0.11/32" protocol="tcp" port="9091"/>
                  <allow address="10.20.0.1" protocol="udp" port="53"/>
                </egress></incus>
                """);
    }
    static DesiredState apply(DeploymentFixture f, String xml) throws Exception {
        var state = f.revision(xml, "version=one\n"); f.engine.reconcile(state); return state;
    }
    static void network(DeploymentFixture f, String type) {
        JsonObject network = new JsonObject(); network.addProperty("type", type); network.add("config", new JsonObject());
        f.mock.seed("/1.0/networks/garden-net", network);
    }
    static String source(DeploymentFixture f, String mount) {
        return f.mock.resources.get(Garden.INSTANCE).getAsJsonObject("devices").getAsJsonObject(mount).get("source").getAsString();
    }
    static String text(DeploymentFixture f, String file) {
        return new String(f.mock.files.entrySet().stream().filter(e -> e.getKey().endsWith("/" + file)).findFirst().orElseThrow().getValue().bytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    static String acl(DeploymentFixture f) {
        return f.mock.resources.keySet().stream().filter(p -> p.startsWith("/1.0/network-acls/")).findFirst().orElseThrow();
    }
}
