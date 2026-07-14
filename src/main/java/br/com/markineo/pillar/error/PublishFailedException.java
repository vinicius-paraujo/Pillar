package br.com.markineo.pillar.error;

public class PublishFailedException extends PillarException {
    public PublishFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
