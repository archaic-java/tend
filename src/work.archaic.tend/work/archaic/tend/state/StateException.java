package work.archaic.tend.state;

import java.io.IOException;

/** Invalid desired state, including unsupported policy or malformed XML. */
public final class StateException extends IOException {
    public StateException(String message) { super(message); }
    public StateException(String message, Throwable cause) { super(message, cause); }
}
