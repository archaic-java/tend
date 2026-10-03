package work.archaic.tend.git;

import java.io.IOException;

/** A failed Git operation or invalid repository file. */
public final class GitException extends IOException {
    public GitException(String message) { super(message); }
    public GitException(String message, Throwable cause) { super(message, cause); }
}
