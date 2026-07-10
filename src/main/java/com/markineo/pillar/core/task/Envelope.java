package com.markineo.pillar.core.task;

import com.markineo.pillar.core.identity.ServerId;
import java.util.Objects;
import java.util.Optional;

// payload is a raw JSON string, not JsonElement, so core never imports a JSON library.
// The codec (in redis) owns all serialization; two-phase decode lets the transport
// inspect headers before any handler deserializes the payload.
// sentAt is epoch-ms as long rather than Instant to stay wire-friendly without a
// custom serialization adapter; the core prefers typed values but the trade-off is
// acceptable while there is a single codec implementation.
public record Envelope(
        int version,
        MessageType type,
        Optional<CorrelationId> correlationId,
        ServerId senderId,
        long sentAt,
        String payload
) {

    public static final int CURRENT_VERSION = 2;

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
