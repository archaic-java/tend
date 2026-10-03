package work.archaic.tend;

/** An expected failed convergence attempt; watch mode may retry it. */
public final class ControllerFailure extends Exception {
    public ControllerFailure(String message, Throwable cause) { super(message, cause); }
}
