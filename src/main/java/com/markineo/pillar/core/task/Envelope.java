package com.markineo.pillar.core.task;

import com.markineo.pillar.core.identity.ServerId;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable transport envelope that wraps every cross-server message.
 *
 * <p>Fields the transport layer must be able to inspect before touching the payload:
 * <ul>
 *   <li>{@code version} — schema version; a receiver that does not understand a future
 *       version can discard the message cleanly instead of misparsing it.</li>
 *   <li>{@code type} — dispatch discriminator; determines which handler runs.</li>
 *   <li>{@code correlationId} — present on request/response pairs (PIL-9); absent
 *       on one-way messages. Optional avoids null at every call site.</li>
 *   <li>{@code senderId} — originating server; required for directed replies and
 *       traceability.</li>
 *   <li>{@code sentAt} — epoch-millisecond timestamp; used to detect stale messages
 *       and clock-drift heuristics.</li>
 *   <li>{@code payload} — raw JSON text of the message body. Kept as a String so
 *       {@code core} never imports a JSON library; the codec (in {@code redis})
 *       handles all serialization, including two-phase decode of the payload.</li>
 * </ul>
 */
public record Envelope(
        int version,
        MessageType type,
        Optional<CorrelationId> correlationId,
        ServerId senderId,
        long sentAt,
        String payload
) {

    /** Current wire-format version. Increment only on breaking schema changes. */
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

    /**
     * Creates a one-way envelope (no correlation id) stamped at the current clock.
     */
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

    /**
     * Creates a request envelope with a fresh correlation id stamped at the current clock.
     * The same correlation id must appear in the response envelope for PIL-9 to match them.
     */
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

    /**
     * Creates a response envelope reusing the caller's correlation id.
     */
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
