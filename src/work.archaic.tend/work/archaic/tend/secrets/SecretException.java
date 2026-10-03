package work.archaic.tend.secrets;

import java.io.IOException;

/** A failed secret declaration or invalid persisted secret. */
public final class SecretException extends IOException {
    public SecretException(String message) { super(message); }
    public SecretException(String message, Throwable cause) { super(message, cause); }
}
