package work.archaic.tend;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import work.archaic.service.logging.v02.*;
import work.archaic.tend.git.GitRepository;
import work.archaic.tend.state.StateReader;

/** Polling retries expected failures; programming defects escape the loop. */
final class Controller {
    private final GitRepository git;
    private final StateReader reader;
    private final Reconciler engine;
    private final Path directory;
    private final Diagnostics diagnostics;
    private final Log log;
    private final Goal goal;
    Controller(GitRepository git, StateReader reader, Reconciler engine, Path directory, Diagnostics diagnostics, Log log) {
        this.git = git; this.reader = reader; this.engine = engine; this.directory = directory;
        this.diagnostics = diagnostics; this.log = log; this.goal = diagnostics.goal("tend.reconcile", log);
    }
    void run(boolean watch, Duration polling) throws ControllerFailure, InterruptedException {
        do {
            attempt(watch);
            if (watch) Thread.sleep(polling);
        } while (watch);
    }
    private void attempt(boolean watch) throws ControllerFailure, InterruptedException {
        try { goal.run(this::pass); }
        catch (ControllerFailure e) {
            if (e.getCause() instanceof InterruptedException interruption) throw interruption;
            if (!watch) throw e;
        }
    }
    private void pass() throws ControllerFailure {
        try {
            var revision = git.fetchMain();
            var desired = reader.read(revision, "incus.xml");
            diagnostics.note("Reconciling commit " + revision.commit());
            engine.reconcile(desired);
            recordSuccess(revision.commit());
            log.write("Reconciled " + revision.commit());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ControllerFailure("Reconciliation interrupted", e);
        } catch (IOException e) { throw new ControllerFailure("Reconciliation failed", e); }
    }
    private void recordSuccess(String commit) throws IOException {
        Path temporary = directory.resolve("last-success.tmp");
        Files.writeString(temporary, commit + "\n");
        Files.move(temporary, directory.resolve("last-success"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
