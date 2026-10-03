package work.archaic.tend.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.*;
import java.util.*;
import work.archaic.service.test.v02.*;
import work.archaic.tend.secrets.SecretStore;
import work.archaic.tend.state.DesiredState.Secret;

public record SecretTests() implements TestSuite {
    public void cases(Collection<TestCase> cases) {
        cases.add(new SigningKey());
        cases.add(new IndependentHashVector());
        cases.add(new OidcSecretDelivery());
        cases.add(new SecretChangesRejected());
        cases.add(new InvalidSecretDeclarations());
        cases.add(new CorruptSecretsRejected());
    }
}
record SigningKey() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var garden = new Garden()) {
            Path directory = garden.state.resolve("secrets");
            var declaration = List.of(new Secret("signing", 32, "rsa-3072", ""));
            byte[] pem = new SecretStore(directory).resolve(declaration).get("signing");
            String text = new String(pem, StandardCharsets.US_ASCII);
            String body = text.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace("\n", "");
            var key = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
            assert key.getModulus().bitLength() == 3072 : "OIDC signing key must have 3072 RSA bits";
            var publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
            var signature = Signature.getInstance("SHA256withRSA"); signature.initSign(key); signature.update(new byte[]{1, 2, 3}); byte[] signed = signature.sign();
            signature.initVerify(publicKey); signature.update(new byte[]{1, 2, 3});
            assert signature.verify(signed) : "Generated PKCS8 key must sign verifiable RS256 messages";
            assert Arrays.equals(pem, new SecretStore(directory).resolve(declaration).get("signing")) : "New store must reuse the persisted signing key";
            assert Files.getPosixFilePermissions(directory).equals(PosixFilePermissions.fromString("rwx------")) : "Secret directory must be private";
            assert Files.getPosixFilePermissions(directory.resolve("signing")).equals(PosixFilePermissions.fromString("rw-------")) : "Persisted signing key must be private";
        }
    }
}
record IndependentHashVector() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var garden = new Garden()) {
            Path directory = garden.state.resolve("secrets"); var store = new SecretStore(directory);
            // Public fixture computed independently with Python hashlib.pbkdf2_hmac and checked with Authelia 4.39.28.
            Files.writeString(directory.resolve("client"), "A".repeat(43));
            String expected = "$pbkdf2-sha512$310000$AAECAwQFBgcICQoLDA0ODw$Mdou.ai5PpPNQZGBjwmBb8W7aXYtuEgHiySta9SJbd3OUwgeeLPlY7kBZY7kgwS/6QRDBb.7sOhj9MzmN5DuvA";
            Files.writeString(directory.resolve("digest"), "tend-secret-v1\npbkdf2-sha512\nclient\n0f007385b6f9d4b7eeb2748605afe1a984a0a3bfa3f014d09e2a784ce9e5cd1a\n" + expected);
            var values = store.resolve(List.of(new Secret("digest", 32, "pbkdf2-sha512", "client"), new Secret("client", 32)));
            assert new String(values.get("digest"), StandardCharsets.US_ASCII).equals(expected) : "PBKDF2 output must match the independent Authelia-compatible vector";
        }
    }
}
record OidcSecretDelivery() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var garden = new Garden(); var mock = new IncusMock()) {
            String xml = OidcDeclarations.xml(); garden.commit(xml, "public config");
            assert CliProcess.invoke(garden, mock) == 0 : "OIDC secret declarations must reconcile through the CLI";
            var declarations = new work.archaic.tend.state.StateReader(Path.of("schema/tend.xsd")).read(garden.git.fetchMain(), "incus.xml").secrets();
            var original = new SecretStore(garden.state.resolve("secrets")).resolve(declarations);
            assert Arrays.equals(mock.files.get(Garden.VOLUME + "/client").bytes(), original.get("client")) : "Application must receive the shared raw client secret";
            assert Arrays.equals(mock.files.get(Garden.VOLUME + "/hash").bytes(), original.get("client-hash")) : "Provider must receive the hash derived from that client secret";
            assert Arrays.equals(mock.files.get(Garden.VOLUME + "/signing").bytes(), original.get("signing")) : "Provider must receive only PKCS8 PEM without storage metadata";
            String shared = mock.resources.get("/1.0/instances/consumer").getAsJsonObject("devices").getAsJsonObject("client").get("source").getAsString();
            assert Arrays.equals(mock.files.get("/1.0/storage-pools/pool/volumes/custom/" + shared + "/value").bytes(), original.get("client")) : "Another consumer must receive the same named client secret through a private mount";
            var files = new HashMap<>(mock.files); int mutations = mock.mutations;
            assert CliProcess.invoke(garden, mock) == 0 : "Controller process restart must reconcile successfully";
            assert mock.mutations == mutations : "Restart must reuse all secret values without activation or writes";
            garden.commit(xml, "changed public config");
            assert CliProcess.invoke(garden, mock) == 0 : "A Git configuration activation must preserve credentials";
            for (String name : List.of("client", "hash", "signing"))
                assert Arrays.equals(files.get(Garden.VOLUME + "/" + name).bytes(), mock.files.get(Garden.VOLUME + "/" + name).bytes()) : "Git changes must preserve generated OIDC material";
            String output = Files.readString(garden.directory.resolve("cli-output"));
            String committed = new String(garden.git.fetchMain().read("incus.xml"), StandardCharsets.UTF_8);
            assert !output.contains("BEGIN PRIVATE KEY") : "CLI logs must not expose signing-key PEM";
            for (byte[] value : original.values()) {
                String text = new String(value, StandardCharsets.US_ASCII);
                assert !output.contains(text) && !committed.contains(text) : "Secret values must stay out of CLI logs and Git state";
            }
        }
    }
}
record SecretChangesRejected() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            var state = f.revision(OidcDeclarations.xml(), "public"); f.engine.reconcile(state);
            int mutations = f.mock.mutations;
            for (String xml : List.of(OidcDeclarations.xml().replace("kind=\"rsa-3072\"", "kind=\"random\""),
                    OidcDeclarations.xml().replace("source=\"client\"", "source=\"session\""),
                    OidcDeclarations.xml().replace("name=\"client\" bytes=\"54\"", "name=\"client\" bytes=\"64\""))) {
                boolean rejected = false;
                try { f.engine.reconcile(f.revision(xml, "public")); } catch (IOException e) { rejected = true; }
                assert rejected && f.mock.mutations == mutations : "Changing a stored generator or source must fail before Incus mutation";
            }
            f.engine.reconcile(state);
            assert f.mock.mutations == mutations : "Rejected changes must leave original credentials intact";
        }
    }
}
record InvalidSecretDeclarations() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var f = new DeploymentFixture()) {
            for (String declaration : List.of("<secret name=\"x\" kind=\"pbkdf2-sha512\" source=\"missing\"/>",
                    "<secret name=\"x\" kind=\"pbkdf2-sha512\" source=\"x\"/>",
                    "<secret name=\"x\" kind=\"rsa-3072\" source=\"session\"/>",
                    "<secret name=\"x\" kind=\"rsa-3072\" bytes=\"32\"/>",
                    "<secret name=\"x\" kind=\"random\" source=\"session\"/>",
                    "<secret name=\"x\" kind=\"pbkdf2-sha512\" source=\"signing\"/><secret name=\"signing\" kind=\"rsa-3072\"/>",
                    "<secret name=\"x\" kind=\"pbkdf2-sha512\" source=\"y\"/><secret name=\"y\" kind=\"pbkdf2-sha512\" source=\"x\"/>")) {
                boolean rejected = false;
                try { f.revision(Garden.xml().replace("<volume", declaration + "<volume"), "public"); } catch (IOException e) { rejected = true; }
                assert rejected && f.mock.mutations == 0 : "Invalid generator graph must fail before deployment";
            }
        }
    }
}
record CorruptSecretsRejected() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (var garden = new Garden(); var mock = new IncusMock()) {
            garden.commit(OidcDeclarations.xml(), "public");
            assert CliProcess.invoke(garden, mock) == 0 : "Initial secret generation must succeed";
            int mutations = mock.mutations;
            for (String name : List.of("signing", "client-hash")) {
                Path file = garden.state.resolve("secrets").resolve(name); byte[] original = Files.readAllBytes(file);
                String sentinel = "PRIVATE-CORRUPTION-MUST-NOT-APPEAR";
                String corrupt = new String(original, StandardCharsets.US_ASCII);
                int headerEnd = 0; for (int i = 0; i < 4; i++) headerEnd = corrupt.indexOf('\n', headerEnd) + 1;
                Files.writeString(file, corrupt.substring(0, headerEnd) + sentinel);
                assert CliProcess.invoke(garden, mock) != 0 : "Corrupt key or hash must fail without regeneration";
                assert mock.mutations == mutations : "Corrupt secret must not mutate Incus";
                assert !Files.readString(garden.directory.resolve("cli-output")).contains(sentinel) : "Failure diagnostics must not contain stored private input";
                Files.write(file, original);
            }
        }
    }
}
final class OidcDeclarations {
    static String xml() {
        return Garden.xml().replace("<volume", """
                <secret name="client-hash" kind="pbkdf2-sha512" source="client"/>
                <secret name="signing" kind="rsa-3072"/>
                <secret name="client" bytes="54"/>
                <volume
                """).replace("</volume>", """
                <file path="/client" secret="client" mode="0400"/>
                <file path="/hash" secret="client-hash" mode="0400"/>
                <file path="/signing" secret="signing" mode="0400"/>
                </volume>
                """).replace("</incus>", """
                <instance name="consumer" fingerprint="%s">
                  <device name="root" type="disk"><config><entry key="path" value="/"/><entry key="pool" value="pool"/></config></device>
                  <mount name="client" secret="client" pool="pool" path="/run/client" uid="1000" gid="1000"/>
                </instance></incus>
                """.formatted(Garden.IMAGE));
    }
}
