package br.com.markineo.pillar.core.task;

import br.com.markineo.pillar.core.identity.ServerId;
import java.util.Objects;
import java.util.Optional;

/**
 * Works like a physical mail envelope.
 * The outside contains the transmission data (sender, message type and wether it's a reply)
 * The sealed letter contains the actual game data stored inside in plain text format (payload).
 * Allows the proxy act as th email carrier. The server can read the outside to determine
 * where to route the message without needing deserialize.
 * @param version
 * @param type
 * @param correlationId
 * @param senderId
 * @param sentAt
 * @param payload
 */
public record Envelope(
        int version,
        MessageType type,
        Optional<CorrelationId> correlationId,
        ServerId senderId,
        long sentAt,
        String payload
) {

    public static final int CURRENT_VERSION = 1;

    public Envelope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(payload, "payload");

        if (version < 1) {
            throw new IllegalArgumentException("Envelope version must be >= 1, got: " + version);
        }
    }

    public static Envelope oneWay(MessageType type, ServerId senderId, String payload) {
        return new Envelope(
                CURRENT_VERSION,
                type,
                Optional.empty(),
                senderId,
                System.currentTimeMillis(),
                payload
        );
    }

    public static Envelope request(MessageType type, ServerId senderId, String payload) {
        return new Envelope(
                CURRENT_VERSION,
                type,
                Optional.of(CorrelationId.generate()),
                senderId,
                System.currentTimeMillis(),
                payload
        );
    }

    public static Envelope response(
            MessageType type,
            CorrelationId correlationId,
            ServerId senderId,
            String payload
    ) {
        return new Envelope(
                CURRENT_VERSION,
                type,
                Optional.of(correlationId),
                senderId,
                System.currentTimeMillis(),
                payload
        );
    }
}
