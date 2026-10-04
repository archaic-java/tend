package work.archaic.tend.test;

import java.nio.file.*;
import java.util.*;
import work.archaic.service.test.v02.*;
import work.archaic.tend.git.GitRepository;
import work.archaic.tend.state.StateReader;
import work.archaic.tend.state.StateException;
import work.archaic.tend.git.GitException;
import work.archaic.tend.secrets.SecretException;
import work.archaic.tend.secrets.SecretStore;

public record StateTests() implements TestSuite {
    public void cases(Collection<TestCase> cases) {
        cases.add(new UnknownElement());
        cases.add(new DuplicateDeclaration());
        cases.add(new MissingSource());
        cases.add(new MissingSecret());
        cases.add(new UnsafeSecretMode());
        cases.add(new ReservedKey());
        cases.add(new Doctype());
        cases.add(new PathEscape());
        cases.add(new SchemaDefaults());
        cases.add(new ImmutableRevision());
        cases.add(new ForcePush());
        cases.add(new UnavailableRemote());
        cases.add(new SecretLength());
    }
}
record UnknownElement() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden()) {
            garden.commit(Garden.xml().replace("</incus>", "<surprise/></incus>"), "first");
            var reader = new StateReader(Path.of("schema/tend.xsd"));
            boolean rejected = false;
            try { reader.read(garden.git.fetchMain(), "incus.xml"); } catch (StateException e) { rejected = true; }
            assert rejected : "Malformed desired state must be rejected before reconciliation";
        }
    }
}
record DuplicateDeclaration() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden()) {
            garden.commit(Garden.xml().replace("<secret name=\"session\" bytes=\"32\"/>", "<secret name=\"session\"/><secret name=\"session\"/>"), "first");
            var reader = new StateReader(Path.of("schema/tend.xsd"));
            boolean rejected = false;
            try { reader.read(garden.git.fetchMain(), "incus.xml"); } catch (StateException e) { rejected = true; }
            assert rejected : "Malformed desired state must be rejected before reconciliation";
        }
    }
}
record MissingSource() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden()) {
            garden.commit(Garden.xml().replace("source=\"service.conf\"", "source=\"missing.conf\""), "first");
            var reader = new StateReader(Path.of("schema/tend.xsd"));
            boolean rejected = false;
            try { reader.read(garden.git.fetchMain(), "incus.xml"); } catch (GitException e) { rejected = true; }
            assert rejected : "Malformed desired state must be rejected before reconciliation";
        }
    }
}
record MissingSecret() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden()) {
            garden.commit(Garden.xml().replace("secret=\"session\"", "secret=\"missing\""), "first");
            var reader = new StateReader(Path.of("schema/tend.xsd"));
            boolean rejected = false;
            try { reader.read(garden.git.fetchMain(), "incus.xml"); } catch (StateException e) { rejected = true; }
            assert rejected : "Malformed desired state must be rejected before reconciliation";
        }
    }
}
record UnsafeSecretMode() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden()) {
            garden.commit(Garden.xml().replace("mode=\"0400\"", "mode=\"0644\""), "first");
            var reader = new StateReader(Path.of("schema/tend.xsd"));
            boolean rejected = false;
            try { reader.read(garden.git.fetchMain(), "incus.xml"); } catch (StateException e) { rejected = true; }
            assert rejected : "Malformed desired state must be rejected before reconciliation";
        }
    }
}
record ReservedKey() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden()) {
            garden.commit(Garden.xml().replace("environment.DEMO", "user.tend.owner"), "first");
            var reader = new StateReader(Path.of("schema/tend.xsd"));
            boolean rejected = false;
            try { reader.read(garden.git.fetchMain(), "incus.xml"); } catch (StateException e) { rejected = true; }
            assert rejected : "Malformed desired state must be rejected before reconciliation";
        }
    }
}
record Doctype() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden()) {
            garden.commit("<!DOCTYPE incus [<!ENTITY example SYSTEM 'file:///etc/passwd'>]>" + Garden.xml(), "first");
            var reader = new StateReader(Path.of("schema/tend.xsd"));
            boolean rejected = false;
            try { reader.read(garden.git.fetchMain(), "incus.xml"); } catch (StateException e) { rejected = true; }
            assert rejected : "Malformed desired state must be rejected before reconciliation";
        }
    }
}
record PathEscape() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden()) {
            garden.commit(Garden.xml().replace("source=\"service.conf\"", "source=\"../service.conf\""), "first");
            var reader = new StateReader(Path.of("schema/tend.xsd"));
            boolean rejected = false;
            try { reader.read(garden.git.fetchMain(), "incus.xml"); } catch (GitException e) { rejected = true; }
            assert rejected : "Malformed desired state must be rejected before reconciliation";
        }
    }
}
record SchemaDefaults() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden()) {
            String xml = Garden.xml();
            String first = garden.commit(xml, "first");
            var revision = garden.git.fetchMain();
            var reader = new StateReader(Path.of("schema/tend.xsd"));
            var state = reader.read(revision, "incus.xml");
            assert state.instances().getFirst().type().equals("container") && state.instances().getFirst().running() : "Schema defaults must describe a running container";

        }
    }
}
record ImmutableRevision() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden()) {
            String xml = Garden.xml();
            String first = garden.commit(xml, "first");
            var revision = garden.git.fetchMain();
            var reader = new StateReader(Path.of("schema/tend.xsd"));
            String second = garden.commit(xml, "second");
            assert !first.equals(second) : "New contents must produce a new revision";
            assert new String(revision.read("service.conf")).equals("first") : "Old revision must retain its configuration after main advances";
            assert new String(garden.git.fetchMain().read("service.conf")).equals("second") : "Next fetch must observe current main";

        }
    }
}
record ForcePush() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden()) {
            String xml = Garden.xml();
            String first = garden.commit(xml, "first");
            var revision = garden.git.fetchMain();
            var reader = new StateReader(Path.of("schema/tend.xsd"));
            garden.commit(xml, "second");
            GitRepository.command(garden.author, "reset", "--hard", first);
            GitRepository.command(garden.author, "push", "--force", "origin", "main");
            assert garden.git.fetchMain().commit().equals(first) : "Rewritten main must become desired state";

        }
    }
}
record UnavailableRemote() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden()) {
            String xml = Garden.xml();
            String first = garden.commit(xml, "first");
            var revision = garden.git.fetchMain();
            var reader = new StateReader(Path.of("schema/tend.xsd"));
            Files.move(garden.remote, garden.directory.resolve("unavailable.git"));
            boolean rejected = false;
            try { garden.git.fetchMain(); } catch (GitException e) { rejected = true; }
            assert rejected : "Fetch failure must not silently deploy a stale revision";
            assert new String(revision.read("service.conf")).equals("first") : "Already resolved revision remains readable offline";

        }
    }
}
record SecretLength() implements TestCase {
    public void run(TestTrail trail) throws Exception {
        try (Garden garden = new Garden()) {
            String xml = Garden.xml();
            String first = garden.commit(xml, "first");
            var revision = garden.git.fetchMain();
            var reader = new StateReader(Path.of("schema/tend.xsd"));
            var store = new SecretStore(garden.state.resolve("secrets"));
            byte[] original = store.getOrCreate("session", 32);
            boolean rejected = false;
            try { store.getOrCreate("session", 64); } catch (SecretException e) { rejected = true; }
            assert rejected && Arrays.equals(original, store.getOrCreate("session", 32)) : "Changing generator parameters must not rotate an existing secret implicitly";

        }
    }
}
