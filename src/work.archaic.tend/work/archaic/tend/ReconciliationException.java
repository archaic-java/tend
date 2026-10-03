package work.archaic.tend;

import java.io.IOException;

/** Ownership conflict or a convergence operation outside the supported slice. */
public final class ReconciliationException extends IOException {
    public ReconciliationException(String message) { super(message); }
    public ReconciliationException(String message, Throwable cause) { super(message, cause); }
}
