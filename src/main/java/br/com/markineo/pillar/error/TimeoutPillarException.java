package br.com.markineo.pillar.error;

public class TimeoutPillarException extends PillarException {
    public TimeoutPillarException(String message) {
        super(message);
    }

    public TimeoutPillarException(String message, Throwable cause) {
        super(message, cause);
    }
}
