package br.com.markineo.pillar.redis.transport;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.task.CorrelationId;
import br.com.markineo.pillar.core.task.Envelope;
import br.com.markineo.pillar.core.task.EnvelopeCodec;
import br.com.markineo.pillar.core.task.MessageType;
import br.com.markineo.pillar.error.PillarException;
import java.util.Optional;

public final class JsonEnvelopeCodec implements EnvelopeCodec {
    // Short field names reduce wire size on high-frequency streams.
    private static final String F_VERSION = "v";
    private static final String F_TYPE = "t";
    private static final String F_CORRELATION = "c";
    private static final String F_SENDER = "s";
    private static final String F_SENT_AT = "ts";
    private static final String F_PAYLOAD = "p";

    private static final int MAX_WIRE_LENGTH = 1024 * 256; // 256 KB

    private final Gson gson;

    public JsonEnvelopeCodec(Gson gson) {
        this.gson = gson;
    }

    @Override
    public String encode(Envelope envelope) {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty(F_VERSION, envelope.version());
            obj.addProperty(F_TYPE, envelope.type().value());
            envelope.correlationId().ifPresent(c -> obj.addProperty(F_CORRELATION, c.value()));
            obj.addProperty(F_SENDER, envelope.senderId().value());
            obj.addProperty(F_SENT_AT, envelope.sentAt());
            obj.addProperty(F_PAYLOAD, envelope.payload());
            return gson.toJson(obj);
        } catch (JsonParseException e) {
            throw new PillarException("Envelope payload is not valid JSON.", e);
        } catch (Exception e) {
            throw new PillarException("Failed to encode envelope.", e);
        }
    }

    /**
     * Passes the string through Gson, calls the read helper methods
     * to extract and type each field.
     * @param wire The message
     * @return An {@link Envelope}.
     */
    @Override
    public Envelope decode(String wire) {
        if (wire == null) {
            throw new PillarException("Wire payload is null.");
        }

        // DoS protection
        if (wire.length() > MAX_WIRE_LENGTH) {
            throw new PillarException("Envelope wire data exceeds maximum allowed size of " + MAX_WIRE_LENGTH + " characters.");
        }

        try {
            JsonObject root = JsonParser.parseString(wire).getAsJsonObject();

            int version = readVersion(root);
            MessageType type = new MessageType(readString(root, F_TYPE));
            ServerId senderId = new ServerId(readString(root, F_SENDER));
            long sentAt = root.get(F_SENT_AT).getAsLong();
            Optional<CorrelationId> correlationId = readCorrelationId(root);
            String payload = readPayload(root);

            return new Envelope(version, type, correlationId, senderId, sentAt, payload);
        } catch (PillarException e) {
            throw e;
        } catch (Exception e) {
            throw new PillarException("Failed to decode envelope: " + e.getMessage(), e);
        }
    }

    private int readVersion(JsonObject root) {
        int version = root.get(F_VERSION).getAsInt();
        if (version > Envelope.CURRENT_VERSION) {
            // Discard rather than misparse: the sender is a newer node we do not understand.
            throw new PillarException(
                    "Unrecognized envelope version " + version
                    + " (max known: " + Envelope.CURRENT_VERSION + ").");
        }
        return version;
    }

    private String readString(JsonObject root, String field) {
        return root.get(field).getAsString();
    }

    private Optional<CorrelationId> readCorrelationId(JsonObject root) {
        if (!root.has(F_CORRELATION) || root.get(F_CORRELATION).isJsonNull()) {
            return Optional.empty();
        }
        return Optional.of(new CorrelationId(root.get(F_CORRELATION).getAsString()));
    }

    private String readPayload(JsonObject root) {
        if (!root.has(F_PAYLOAD) || root.get(F_PAYLOAD).isJsonNull()) {
            throw new PillarException("Envelope is missing required payload field.");
        }
        return readString(root, F_PAYLOAD);
    }

    /**
     * Unpacks the content and converts the raw text into a usable object.
     * <p>
     * Note: This method only supports standard flat objects (e.g., MyCustomMessage.class).
     * Currently, it cannot unpack generic collections (such as a List of items), because
     * the converter needs a concrete Class to know exactly how to build the object.
     *
     * @param envelope The sealed data.
     * @param type The exact class format you want the data converted into.
     * @return The populated object.
     */
    @Override
    public <T> T decodePayload(Envelope envelope, Class<T> type) {
        try {
            return gson.fromJson(envelope.payload(), type);
        } catch (Exception e) {
            throw new PillarException(
                    "Failed to decode payload as " + type.getSimpleName() + ": " + e.getMessage(), e
            );
        }
    }

    public String encodePayload(Object payloadObject) {
        return gson.toJson(payloadObject);
    }
}
