package work.archaic.tend.secrets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import work.archaic.tend.state.DesiredState.Secret;

/** Stable named secrets stored on the controller's private persistent volume. */
public final class SecretStore {
    private static final int ITERATIONS = 310000;
    private final Path directory;
    public SecretStore(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (Files.isSymbolicLink(directory)) throw new SecretException("Secret directory cannot be a symlink");
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
    /** Resolve generators before derived hashes; declaration order has no significance. */
    public synchronized Map<String, byte[]> resolve(List<Secret> declarations) throws IOException {
        Map<String, byte[]> values = new HashMap<>();
        for (var secret : declarations) switch (secret.kind()) {
            case "random" -> values.put(secret.name(), getOrCreate(secret.name(), secret.bytes()));
            case "rsa-3072" -> values.put(secret.name(), typed(secret, null));
            case "pbkdf2-sha512" -> { }
            default -> throw new SecretException("Unsupported secret generator");
        }
        for (var secret : declarations) if (secret.kind().equals("pbkdf2-sha512")) {
            byte[] source = values.get(secret.source());
            if (source == null) throw new SecretException("Missing hash source");
            values.put(secret.name(), typed(secret, source));
        }
        return values;
    }
    public synchronized byte[] getOrCreate(String name, int bytes) throws IOException {
        if (bytes < 32 || bytes > 128) throw new SecretException("Invalid secret declaration");
        Path file = file(name);
        if (!Files.exists(file)) {
            byte[] random = new byte[bytes]; new SecureRandom().nextBytes(random);
            create(file, Base64.getUrlEncoder().withoutPadding().encode(random));
        }
        byte[] stored = read(file);
        try {
            if (Base64.getUrlDecoder().decode(stored).length != bytes) throw new SecretException("Stored secret length differs; explicit rotation required");
        } catch (IllegalArgumentException e) { throw new SecretException("Stored secret is invalid"); }
        return stored;
    }
    private byte[] typed(Secret secret, byte[] source) throws IOException {
        Path file = file(secret.name());
        try {
            String digest = source == null ? "" : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
            String header = "tend-secret-v1\n" + secret.kind() + "\n" + secret.source() + "\n" + digest + "\n";
            if (!Files.exists(file)) {
                String value = source == null ? rsa() : hash(source, randomSalt());
                create(file, (header + value).getBytes(StandardCharsets.US_ASCII));
            }
            String stored = new String(read(file), StandardCharsets.US_ASCII);
            if (!stored.startsWith(header)) throw new SecretException("Stored secret generator or source differs; explicit rotation required");
            String value = stored.substring(header.length());
            if (source == null) validateRsa(value); else validateHash(value, source);
            return value.getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Provider exceptions may contain input. Keep diagnostics independent of secret material.
            throw new SecretException("Stored secret or cryptographic operation is invalid");
        }
    }
    private static String rsa() throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(3072);
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
    }
    private static void validateRsa(String pem) throws GeneralSecurityException {
        String begin = "-----BEGIN PRIVATE KEY-----\n", end = "\n-----END PRIVATE KEY-----\n";
        if (!pem.startsWith(begin) || !pem.endsWith(end)) throw new IllegalArgumentException();
        String body = pem.substring(begin.length(), pem.length() - end.length()).replace("\n", "");
        var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        if (!(key instanceof RSAPrivateCrtKey rsa) || rsa.getModulus().bitLength() != 3072 || !rsa.getPublicExponent().equals(java.math.BigInteger.valueOf(65537)))
            throw new IllegalArgumentException();
    }
    private static byte[] randomSalt() {
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt); return salt;
    }
    private static String hash(byte[] source, byte[] salt) throws GeneralSecurityException {
        char[] password = new String(source, StandardCharsets.US_ASCII).toCharArray();
        var spec = new PBEKeySpec(password, salt, ITERATIONS, 512);
        Arrays.fill(password, '\0');
        try {
            byte[] result = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).getEncoded();
            return "$pbkdf2-sha512$" + ITERATIONS + "$" + adaptedBase64(salt) + "$" + adaptedBase64(result);
        } finally { spec.clearPassword(); }
    }
    private static String adaptedBase64(byte[] value) {
        return Base64.getEncoder().withoutPadding().encodeToString(value).replace('+', '.');
    }
    private static void validateHash(String value, byte[] source) throws GeneralSecurityException {
        String[] fields = value.split("\\$", -1);
        if (fields.length != 5 || !fields[1].equals("pbkdf2-sha512") || !fields[2].equals(Integer.toString(ITERATIONS)))
            throw new IllegalArgumentException();
        byte[] salt = Base64.getDecoder().decode(fields[3].replace('.', '+'));
        if (salt.length != 16 || !MessageDigest.isEqual(value.getBytes(StandardCharsets.US_ASCII), hash(source, salt).getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException();
    }
    private Path file(String name) throws IOException {
        if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}")) throw new SecretException("Invalid secret declaration");
        Path file = directory.resolve(name);
        if (Files.isSymbolicLink(file) || (Files.exists(file) && !Files.isRegularFile(file))) throw new SecretException("Secret file must be a regular file");
        return file;
    }
    private byte[] read(Path file) throws IOException {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return Files.readAllBytes(file);
    }
    private void create(Path file, byte[] value) throws IOException {
        Path temporary = Files.createTempFile(directory, ".secret-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.write(temporary, value);
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
}
