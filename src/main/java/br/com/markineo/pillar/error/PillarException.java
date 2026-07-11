package br.com.markineo.pillar.error;

public class PillarException extends RuntimeException {

    public PillarException(String message) {
        super(message);
    }

    public PillarException(String message, Throwable cause) {
        super(message, cause);
    }
}
