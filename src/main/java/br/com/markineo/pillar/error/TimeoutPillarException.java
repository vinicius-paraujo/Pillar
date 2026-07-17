package br.com.markineo.pillar.error;

/**
 * Thrown when a {@link br.com.markineo.pillar.api.Messaging#request} does not
 * receive a response before its timeout. The target may still have received and
 * handled the request; this only means no response came back in time. It is also
 * what the caller sees if the target's handler threw instead of responding, so a
 * timeout does not always mean the network dropped anything.
 */
public class TimeoutPillarException extends PillarException {
    public TimeoutPillarException(String message) {
        super(message);
    }

    public TimeoutPillarException(String message, Throwable cause) {
        super(message, cause);
    }
}
