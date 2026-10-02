package work.archaic.tend.test;

import java.nio.file.*;
import java.util.*;
import work.archaic.service.test.v02.*;
import work.archaic.tend.git.GitRepository;
import work.archaic.tend.state.StateReader;
import work.archaic.tend.secrets.SecretStore;

public record StateTests() implements TestSuite {
    public void cases(Collection<TestCase> cases) {
        for (String scenario : List.of("defaults", "unknown-element", "duplicate", "missing-source", "missing-secret",
                "unsafe-secret-mode", "reserved-key", "doctype", "path-escape", "immutable-revision", "force-push",
                "remote-unavailable", "secret-length")) cases.add(new StateCase(scenario));
    }
}
record StateCase(String scenario) implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden()) {
            String xml = Garden.xml();
            var reader = new StateReader(Path.of("schema/tend.xsd"));
            switch (scenario) {
                case "unknown-element" -> xml = xml.replace("</incus>", "<surprise/></incus>");
                case "duplicate" -> xml = xml.replace("<secret name=\"session\" bytes=\"32\"/>", "<secret name=\"session\"/><secret name=\"session\"/>");
                case "missing-source" -> xml = xml.replace("source=\"service.conf\"", "source=\"missing.conf\"");
                case "missing-secret" -> xml = xml.replace("secret=\"session\"", "secret=\"missing\"");
                case "unsafe-secret-mode" -> xml = xml.replace("mode=\"0400\"", "mode=\"0644\"");
                case "reserved-key" -> xml = xml.replace("environment.DEMO", "user.tend.owner");
                case "doctype" -> xml = "<!DOCTYPE incus [<!ENTITY example SYSTEM 'file:///etc/passwd'>]>" + xml;
                case "path-escape" -> xml = xml.replace("source=\"service.conf\"", "source=\"../service.conf\"");
            }
            String first = garden.commit(xml, "first");
            var revision = garden.git.fetchMain();
            if (Set.of("unknown-element", "duplicate", "missing-source", "missing-secret", "unsafe-secret-mode", "reserved-key", "doctype", "path-escape").contains(scenario)) {
                boolean rejected = false;
                try { reader.read(revision, "incus.xml"); } catch (Exception e) { rejected = true; trail.note(e.getClass().getSimpleName()); }
                assert rejected : "Malformed desired state must be rejected before reconciliation";
            } else if (scenario.equals("defaults")) {
                var state = reader.read(revision, "incus.xml");
                assert state.instances().getFirst().type().equals("container") && state.instances().getFirst().running() : "Schema defaults must describe a running container";
            } else if (scenario.equals("immutable-revision")) {
                String second = garden.commit(xml, "second");
                assert !first.equals(second) : "New contents must produce a new revision";
                assert new String(revision.read("service.conf")).equals("first") : "Old revision must retain its configuration after main advances";
                assert new String(garden.git.fetchMain().read("service.conf")).equals("second") : "Next fetch must observe current main";
            } else if (scenario.equals("force-push")) {
                garden.commit(xml, "second");
                GitRepository.command(garden.author, "reset", "--hard", first);
                GitRepository.command(garden.author, "push", "--force", "origin", "main");
                assert garden.git.fetchMain().commit().equals(first) : "Rewritten main must become desired state";
            } else if (scenario.equals("remote-unavailable")) {
                Files.move(garden.remote, garden.directory.resolve("unavailable.git"));
                boolean rejected = false;
                try { garden.git.fetchMain(); } catch (java.io.IOException e) { rejected = true; }
                assert rejected : "Fetch failure must not silently deploy a stale revision";
                assert new String(revision.read("service.conf")).equals("first") : "Already resolved revision remains readable offline";
            } else if (scenario.equals("secret-length")) {
                var store = new SecretStore(garden.state.resolve("secrets"));
                byte[] original = store.getOrCreate("session", 32);
                boolean rejected = false;
                try { store.getOrCreate("session", 64); } catch (java.io.IOException e) { rejected = true; }
                assert rejected && Arrays.equals(original, store.getOrCreate("session", 32)) : "Changing generator parameters must not rotate an existing secret implicitly";
            }
        }
    }
}
