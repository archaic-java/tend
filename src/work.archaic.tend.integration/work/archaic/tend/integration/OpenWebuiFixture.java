package work.archaic.tend.integration;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Minimal actual OCI consumer sharing the existing issuer, private users and browser. */
final class OpenWebuiFixture {
    static final String INSTANCE = "tend-ci-openwebui";
    static final String ORIGIN = "https://webui.garden.internal";
    static final String CLIENT = "webui-smoke";
    private final IncusCommands incus;
    private final RealGarden garden;
    private final GrafanaFixture grafana;
    private final PrivateCommands privateCommands;
    OpenWebuiFixture(IncusCommands incus, RealGarden garden, GrafanaFixture grafana) {
        this.incus = incus; this.garden = garden; this.grafana = grafana;
        privateCommands = new PrivateCommands(garden.directory);
    }
    String provider() {
        // v0.11.4 admits its first user and admin claims independently of allowed roles.
        // The issuer MUST gate every code on ai-users, including bootstrap and returning users.
        return grafana.provider().replace("    claims_policies:\n", """
                      webui:
                        default_policy: deny
                        rules:
                          - policy: one_factor
                            subject: [["group:ai-users"]]
                    claims_policies:
                """) + """
                      - client_id: webui-smoke
                        client_name: WebUI smoke
                        client_secret: '{{ secret "/etc/tend-webui-hash/value" }}'
                        public: false
                        authorization_policy: webui
                        claims_policy: garden
                        consent_mode: explicit
                        redirect_uris: ["https://webui.garden.internal/oauth/oidc/callback"]
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
    String xml() throws IOException {
        String fingerprint = Files.readString(Path.of("out/incus-smoke/openwebui-fingerprint.txt")).strip();
        if (!fingerprint.matches("[a-f0-9]{64}")) throw new IOException("Expected cached Open WebUI OCI fingerprint");
        return FixtureXml.ordered(grafana.xml().replace("<secret name=\"oidc-signing\"", """
                <secret name="webui-client" bytes="54"/><secret name="webui-session"/>
                <secret name="webui-hash" kind="pbkdf2-sha512" source="webui-client"/>
                <configuration name="webui-launch"><file path="/start.sh" source="webui-start.sh"/></configuration>
                <configuration name="webui-ca"><file path="/root.crt" source="root.crt"/></configuration>
                <volume pool="tend-ci-pool" name="tend-ci-webui-data"><config>
                  <entry key="security.shifted" value="true"/><entry key="initial.uid" value="0"/>
                  <entry key="initial.gid" value="0"/><entry key="initial.mode" value="0700"/>
                </config></volume>
                <secret name="oidc-signing"
                """).replace("<mount name=\"oidc-hash\"", """
                <mount name="webui-hash" secret="webui-hash" pool="tend-ci-pool" path="/etc/tend-webui-hash"/>
                <mount name="oidc-hash"
                """).replace("</incus>", """
                <instance name="tend-ci-openwebui" fingerprint="%s"><config>
                  <entry key="oci.uid" value="0"/><entry key="oci.gid" value="0"/>
                  <entry key="oci.entrypoint" value="/bin/sh /etc/tend-webui-launch/start.sh"/>
                  <entry key="oci.dns.nameservers" value="10.77.1.40"/>
                  <entry key="environment.SSL_CERT_FILE" value="/etc/tend-webui-ca/root.crt"/>
                  <entry key="environment.REQUESTS_CA_BUNDLE" value="/etc/tend-webui-ca/root.crt"/>
                  <entry key="environment.WEBUI_URL" value="https://webui.garden.internal"/>
                  <entry key="environment.WEBUI_AUTH" value="true"/>
                  <entry key="environment.WEBUI_SESSION_COOKIE_SECURE" value="true"/>
                  <entry key="environment.WEBUI_AUTH_COOKIE_SECURE" value="true"/>
                  <entry key="environment.ENABLE_LOGIN_FORM" value="false"/>
                  <entry key="environment.ENABLE_PASSWORD_AUTH" value="false"/>
                  <entry key="environment.ENABLE_SIGNUP" value="false"/>
                  <entry key="environment.ENABLE_OAUTH_SIGNUP" value="true"/>
                  <entry key="environment.ENABLE_OAUTH" value="true"/>
                  <entry key="environment.OAUTH_CLIENT_ID" value="webui-smoke"/>
                  <entry key="environment.OAUTH_PROVIDER_NAME" value="Authelia"/>
                  <entry key="environment.OPENID_PROVIDER_URL" value="https://auth.garden.internal/.well-known/openid-configuration"/>
                  <entry key="environment.OPENID_REDIRECT_URI" value="https://webui.garden.internal/oauth/oidc/callback"/>
                  <entry key="environment.OAUTH_SCOPES" value="openid profile email groups"/>
                  <entry key="environment.OAUTH_CODE_CHALLENGE_METHOD" value="S256"/>
                  <entry key="environment.OAUTH_TOKEN_ENDPOINT_AUTH_METHOD" value="client_secret_basic"/>
                  <entry key="environment.ENABLE_OAUTH_ROLE_MANAGEMENT" value="true"/>
                  <entry key="environment.OAUTH_ROLES_CLAIM" value="groups"/>
                  <entry key="environment.OAUTH_ALLOWED_ROLES" value="ai-users"/>
                  <entry key="environment.OAUTH_ADMIN_ROLES" value="admins"/>
                  <entry key="environment.OAUTH_MERGE_ACCOUNTS_BY_EMAIL" value="false"/>
                  <entry key="environment.ENABLE_PERSISTENT_CONFIG" value="false"/>
                  <entry key="environment.ENABLE_OAUTH_PERSISTENT_CONFIG" value="false"/>
                  <entry key="environment.ENABLE_OLLAMA_API" value="false"/>
                  <entry key="environment.ENABLE_OPENAI_API" value="true"/>
                  <entry key="environment.OPENAI_API_BASE_URL" value="http://127.0.0.1:18080/v1"/>
                  <entry key="environment.CORS_ALLOW_ORIGIN" value="https://webui.garden.internal"/>
                  <entry key="environment.FORWARDED_ALLOW_IPS" value="10.77.1.0/24"/>
                  <entry key="environment.OFFLINE_MODE" value="true"/>
                </config>
                <device name="root" type="disk"><config><entry key="path" value="/"/><entry key="pool" value="tend-ci-pool"/></config></device>
                <device name="eth0" type="nic"><config><entry key="network" value="tend-ci-ovn"/><entry key="name" value="eth0"/><entry key="ipv4.address" value="10.77.1.45"/></config></device>
                <device name="data" type="disk"><config><entry key="path" value="/app/backend/data"/><entry key="pool" value="tend-ci-pool"/><entry key="source" value="tend-ci-webui-data"/></config></device>
                <mount name="launch" configuration="webui-launch" pool="tend-ci-pool" path="/etc/tend-webui-launch" mode="0555"/>
                <mount name="ca" configuration="webui-ca" pool="tend-ci-pool" path="/etc/tend-webui-ca" mode="0644"/>
                <mount name="client" secret="webui-client" pool="tend-ci-pool" path="/etc/tend-webui-client"/>
                <mount name="session" secret="webui-session" pool="tend-ci-pool" path="/etc/tend-webui-session"/>
                </instance>
                <ingress name="webui" host="webui.garden.internal" instance="tend-ci-openwebui" device="eth0" port="8080"><public/></ingress>
                </incus>
                """.formatted(fingerprint)));
    }
    void ready() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        do {
            var response = incus.run(Duration.ofSeconds(10), "exec", INSTANCE, "--", "curl", "--silent", "--max-time", "8",
                    "--output", "/dev/null", "--write-out", "%{http_code}", "http://127.0.0.1:8080/health");
            if (response.status() == 0 && response.output().strip().equals("200")) return;
            Thread.sleep(500);
        } while (System.nanoTime() < deadline);
        throw new IOException("Open WebUI did not become healthy");
    }
    void staleDatabaseSettings() throws Exception {
        // Public synthetic flags only; no credentials or account rows are read or emitted.
        incus.require("exec", INSTANCE, "--", "python3", "-c", """
                import sqlite3,json
                with sqlite3.connect('/app/backend/data/webui.db') as db:
                    for key,value in {'ui.enable_login_form':True,'ui.enable_signup':True,'oauth.allowed_roles':['observers']}.items():
                        db.execute('INSERT OR REPLACE INTO config (key,value,updated_at) VALUES (?,?,0)',(key,json.dumps(value)))
                """);
    }
    boolean leaked(Set<String> browserValues) throws Exception {
        var values = new HashSet<>(browserValues);
        values.add(privateCommands.incus("exec", INSTANCE, "--", "cat", "/etc/tend-webui-client/value").strip());
        values.add(privateCommands.incus("exec", INSTANCE, "--", "cat", "/etc/tend-webui-session/value").strip());
        String console = privateCommands.incus("console", INSTANCE, "--show-log");
        for (String value : values) if (value.length() >= 16 && console.contains(value)) return true;
        try (var files = Files.walk(Path.of("out/incus-smoke"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String text = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
                for (String value : values) if (value.length() >= 16 && text.contains(value)) return true;
            }
        }
        return false;
    }
}
