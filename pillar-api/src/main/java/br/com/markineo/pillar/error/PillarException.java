package br.com.markineo.pillar.error;

/**
 * Base type for structural failures Pillar throws: a Redis connection rejected, a
 * malformed config, a request that timed out. Never a raw checked exception.
 *
 * <p>This is not the base type for every exception the API can throw. Invalid
 * arguments (a blank ID, an unknown target server, a null lease) surface as plain
 * {@link IllegalArgumentException} or {@link NullPointerException}, not as a
 * {@code PillarException}. Catching {@code PillarException} does not catch argument
 * misuse.
 */
public class PillarException extends RuntimeException {

    public PillarException(String message) {
        super(message);
    }

    public PillarException(String message, Throwable cause) {
        super(message, cause);
    }
}
