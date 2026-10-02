package work.archaic.tend.secrets;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/** Stable named secrets stored on the controller's private persistent volume. */
public final class SecretStore {
    private final Path directory;
    public SecretStore(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (Files.isSymbolicLink(directory)) throw new IOException("Secret directory cannot be a symlink");
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
    public synchronized byte[] getOrCreate(String name, int bytes) throws IOException {
        if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}") || bytes < 32 || bytes > 128)
            throw new IOException("Invalid secret declaration");
        Path file = directory.resolve(name);
        if (Files.isSymbolicLink(file)) throw new IOException("Secret file cannot be a symlink");
        if (!Files.exists(file)) {
            byte[] random = new byte[bytes];
            new SecureRandom().nextBytes(random);
            byte[] encoded = Base64.getUrlEncoder().withoutPadding().encode(random);
            Path temporary = Files.createTempFile(directory, ".secret-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                Files.write(temporary, encoded);
                try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        byte[] stored = Files.readAllBytes(file);
        try {
            if (Base64.getUrlDecoder().decode(stored).length != bytes) throw new IOException("Stored secret length differs; explicit rotation required");
        } catch (IllegalArgumentException e) { throw new IOException("Stored secret is invalid", e); }
        return stored;
    }
}
