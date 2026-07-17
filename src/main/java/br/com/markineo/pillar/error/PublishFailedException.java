package br.com.markineo.pillar.error;

/**
 * Signals that a message could not be published to the transport at all; the send
 * never left the node. Distinct from a timeout, where the message went out but no
 * response came back in time.
 *
 * <p>{@link br.com.markineo.pillar.api.Messaging} delivers this through the returned
 * future rather than throwing it, so a caller that discards the future never sees it.
 */
public class PublishFailedException extends PillarException {
    public PublishFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
