package work.archaic.tend.incus;

import java.io.IOException;

/** A failed Incus request or invalid protocol response. */
public final class IncusException extends IOException {
    public IncusException(String message) { super(message); }
    public IncusException(String message, Throwable cause) { super(message, cause); }
}
