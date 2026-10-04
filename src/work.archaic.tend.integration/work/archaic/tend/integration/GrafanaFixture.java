package work.archaic.tend.integration;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real cached OCI consumer. Operator DNS bootstrap and private evidence stay test-only. */
final class GrafanaFixture {
    static final String INSTANCE = "tend-ci-grafana";
    static final String ORIGIN = "https://grafana.garden.internal";
    static final String CLIENT = "grafana-smoke";
    private final IncusCommands incus;
    private final RealGarden garden;
    private final AuthorizationFixture authorization;
    GrafanaFixture(IncusCommands incus, RealGarden garden, AuthorizationFixture authorization) {
        this.incus = incus; this.garden = garden; this.authorization = authorization;
    }
    String provider() {
        // This client admits observers as well as admins so Grafana's own group check is exercised.
        return authorization.oidcConfiguration() + """
                      - client_id: grafana-smoke
                        client_name: Grafana smoke
                        client_secret: '{{ secret "/etc/tend-grafana-hash/value" }}'
                        public: false
                        authorization_policy: one_factor
                        claims_policy: garden
                        consent_mode: explicit
                        redirect_uris: ["https://grafana.garden.internal/login/generic_oauth"]
                        scopes: [openid, profile, email, groups]
                        response_types: [code]
                        response_modes: [query]
                        grant_types: [authorization_code]
                        require_pkce: true
                        pkce_challenge_method: S256
                        token_endpoint_auth_method: client_secret_basic
                        id_token_signed_response_alg: RS256
                """;
    }
    String configuration(String groups, String fallbackRole) {
        return """
                [server]
                root_url = https://grafana.garden.internal
                [analytics]
                reporting_enabled = false
                check_for_updates = false
                check_for_plugin_updates = false
                [plugins]
                preinstall_disabled = true
                [auth]
                disable_login_form = true
                [auth.basic]
                enabled = false
                [auth.generic_oauth]
                enabled = true
                name = Authelia
                auto_login = true
                client_id = grafana-smoke
                scopes = openid profile email groups
                auth_url = https://auth.garden.internal/api/oidc/authorization
                token_url = https://auth.garden.internal/api/oidc/token
                api_url = https://auth.garden.internal/api/oidc/userinfo
                login_attribute_path = preferred_username
                groups_attribute_path = groups
                allowed_groups = %s
                role_attribute_path = contains(groups[*], 'admins') && 'GrafanaAdmin' || '%s'
                role_attribute_strict = true
                allow_assign_grafana_admin = true
                use_pkce = true
                auth_style = InHeader
                tls_client_ca = /etc/tend-grafana/root.crt
                validate_id_token = true
                jwk_set_url = https://auth.garden.internal/jwks.json
                [log]
                level = warn
                """.formatted(groups, fallbackRole);
    }
    String xml() throws IOException {
        String fingerprint = Files.readString(Path.of("out/incus-smoke/grafana-fingerprint.txt")).strip();
        if (!fingerprint.matches("[a-f0-9]{64}")) throw new IOException("Expected cached Grafana OCI fingerprint");
        return authorization.oidcXml()
                .replace("<secret name=\"oidc-signing\"", """
                        <secret name="grafana-client" bytes="54"/>
                        <secret name="grafana-hash" kind="pbkdf2-sha512" source="grafana-client"/>
                        <secret name="grafana-admin"/><secret name="grafana-key"/>
                        <secret name="oidc-signing"
                        """)
                .replace("<configuration name=\"oidc\">", """
                        <configuration name="grafana">
                          <file path="/grafana.ini" source="grafana.ini"/>
                          <file path="/root.crt" source="root.crt"/>
                        </configuration>
                        <configuration name="oidc">
                        """)
                .replace("<instance name=\"tend-ci-auth-gateway\"", """
                        <volume pool="tend-ci-pool" name="tend-ci-grafana-data"><config>
                          <entry key="security.shifted" value="true"/>
                          <entry key="initial.uid" value="472"/><entry key="initial.gid" value="0"/>
                          <entry key="initial.mode" value="0700"/>
                        </config></volume>
                        <instance name="tend-ci-auth-gateway"
                        """)
                .replace("<mount name=\"oidc-hash\"", """
                        <mount name="grafana-hash" secret="grafana-hash" pool="tend-ci-pool" path="/etc/tend-grafana-hash"/>
                        <mount name="oidc-hash"
                        """)
                .replace("<ingress-gateway ", """
                        <instance name="tend-ci-grafana" fingerprint="%s">
                          <config>
                            <entry key="oci.uid" value="472"/><entry key="oci.gid" value="0"/>
                            <entry key="oci.dns.nameservers" value="10.77.1.40"/>
                            <entry key="environment.GF_PATHS_CONFIG" value="/etc/tend-grafana/grafana.ini"/>
                            <entry key="environment.SSL_CERT_FILE" value="/etc/tend-grafana/root.crt"/>
                            <entry key="environment.GF_SECURITY_ADMIN_PASSWORD__FILE" value="/etc/tend-grafana-admin/value"/>
                            <entry key="environment.GF_SECURITY_SECRET_KEY__FILE" value="/etc/tend-grafana-key/value"/>
                            <entry key="environment.GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET__FILE" value="/etc/tend-grafana-client/value"/>
                          </config>
                          <device name="root" type="disk"><config><entry key="path" value="/"/><entry key="pool" value="tend-ci-pool"/></config></device>
                          <device name="eth0" type="nic"><config><entry key="network" value="tend-ci-ovn"/><entry key="name" value="eth0"/><entry key="ipv4.address" value="10.77.1.44"/></config></device>
                          <device name="data" type="disk"><config><entry key="path" value="/var/lib/grafana"/><entry key="pool" value="tend-ci-pool"/><entry key="source" value="tend-ci-grafana-data"/></config></device>
                          <mount name="config" configuration="grafana" pool="tend-ci-pool" path="/etc/tend-grafana" uid="472" mode="0644"/>
                          <mount name="client" secret="grafana-client" pool="tend-ci-pool" path="/etc/tend-grafana-client" uid="472"/>
                          <mount name="admin" secret="grafana-admin" pool="tend-ci-pool" path="/etc/tend-grafana-admin" uid="472"/>
                          <mount name="key" secret="grafana-key" pool="tend-ci-pool" path="/etc/tend-grafana-key" uid="472"/>
                        </instance>
                        <ingress-gateway\s
                        """.formatted(fingerprint))
                .replace("</incus>", """
                        <ingress name="grafana" host="grafana.garden.internal" instance="tend-ci-grafana" device="eth0" port="3000"><public/></ingress>
                        </incus>
                        """);
    }
    void dns() throws Exception {
        // The fixture's private domain is intentionally absent from public DNS.
        incus.require("exec", AuthorizationFixture.GATEWAY, "--", "dnsmasq", "--no-hosts", "--no-resolv",
                "--address=/auth.garden.internal/10.77.1.40", "--listen-address=10.77.1.40", "--bind-interfaces");
    }
    void ready() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        do {
            var reply = incus.run(Duration.ofSeconds(10), "exec", "tend-ci-auth-client", "--", "curl", "--silent", "--show-error", "--noproxy", "*",
                    "--connect-timeout", "2", "--max-time", "8", "--cacert", "/root/caddy-ca.crt", "--resolve", "grafana.garden.internal:443:10.77.1.40",
                    "--output", "/dev/null", "--write-out", "%{http_code}", ORIGIN + "/api/health");
            if (reply.status() == 0 && reply.output().strip().equals("200")) return;
            Thread.sleep(300);
        } while (System.nanoTime() < deadline);
        throw new IOException("Grafana did not become healthy through the HTTPS gateway");
    }
    record Evidence(boolean leaked, boolean groupDenied, boolean roleDenied) {}
    Evidence evidence(Set<String> browserValues) throws Exception {
        var values = new HashSet<>(browserValues);
        List<Path> evidence = new ArrayList<>();
        Path console = garden.directory.resolve("grafana-console.private");
        privateCommand(console, "console", INSTANCE, "--show-log"); evidence.add(console);
        String applicationLog = Files.readString(console);
        boolean groupDenied = applicationLog.contains("user not a member of one of the required groups");
        boolean roleDenied = applicationLog.contains("could not evaluate any valid roles using IdP provided data");
        Path auth = garden.directory.resolve("grafana-authelia-log.private");
        privateCommand(auth, "exec", AuthorizationFixture.AUTH, "--", "cat", "/var/log/tend-authelia.log"); evidence.add(auth);
        for (String mount : List.of("client", "admin", "key")) {
            Path file = garden.directory.resolve("grafana-" + mount + ".private");
            privateCommand(file, "exec", INSTANCE, "--", "cat", "/etc/tend-grafana-" + mount + "/value");
            values.add(Files.readString(file).strip()); Files.delete(file);
        }
        try (var paths = Files.walk(Path.of("out/incus-smoke"))) { evidence.addAll(paths.filter(Files::isRegularFile).toList()); }
        for (Path file : evidence) {
            String text = Files.readString(file);
            for (String value : values) if (value.length() >= 16 && text.contains(value)) return new Evidence(true, groupDenied, roleDenied);
            if (text.contains("-----BEGIN PRIVATE KEY-----") || text.contains("$pbkdf2-sha512$")
                    || java.util.regex.Pattern.compile("eyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").matcher(text).find()) return new Evidence(true, groupDenied, roleDenied);
        }
        return new Evidence(false, groupDenied, roleDenied);
    }
    private static void privateCommand(Path output, String... args) throws Exception {
        var command = new ArrayList<>(List.of("sudo", "-n", "incus")); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        process.getOutputStream().close();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException("Cannot inspect private Grafana evidence");
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
