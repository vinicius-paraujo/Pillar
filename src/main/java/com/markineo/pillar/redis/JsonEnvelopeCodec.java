package com.markineo.pillar.redis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.task.CorrelationId;
import com.markineo.pillar.core.task.Envelope;
import com.markineo.pillar.core.task.EnvelopeCodec;
import com.markineo.pillar.core.task.MessageType;
import com.markineo.pillar.error.PillarException;
import java.util.Optional;

/**
 * Gson-backed envelope codec.
 *
 * <p>Gson is already a transitive dep of Jedis 5 and is relocated under
 * {@code com.markineo.pillar.lib.gson} in the shadow jar, so this codec adds zero
 * new shaded weight. Keeping the codec in {@code redis} (not {@code core}) ensures
 * that the Gson import stays at the adapter boundary and never leaks into pure domain
 * code.
 *
 * <p>Wire format — a single JSON object:
 * <pre>
 * {
 *   "v":  1,
 *   "t":  "pillar.ping",
 *   "c":  "550e8400-...",   // absent when no correlation id
 *   "s":  "paper-01",
 *   "ts": 1720000000000,
 *   "p":  { ... }           // inlined payload object, not a JSON string
 * }
 * </pre>
 * Short field names reduce wire size across high-frequency streams (heartbeats, acks).
 */
public final class JsonEnvelopeCodec implements EnvelopeCodec {

    private static final String F_VERSION = "v";
    private static final String F_TYPE = "t";
    private static final String F_CORRELATION = "c";
    private static final String F_SENDER = "s";
    private static final String F_SENT_AT = "ts";
    private static final String F_PAYLOAD = "p";

    private final Gson gson;

    public JsonEnvelopeCodec() {
        // Disable HTML escaping so forward slashes in role strings survive round-trips.
        this.gson = new GsonBuilder().disableHtmlEscaping().create();
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
            // Re-parse the payload string so it is inlined as a JSON subtree, not a
            // double-encoded string. If the payload is not valid JSON we fail fast here
            // rather than writing corrupt data to the stream.
            obj.add(F_PAYLOAD, JsonParser.parseString(envelope.payload()));
            return gson.toJson(obj);
        } catch (JsonParseException e) {
            throw new PillarException("Envelope payload is not valid JSON.", e);
        } catch (Exception e) {
            throw new PillarException("Failed to encode envelope.", e);
        }
    }

    @Override
    public Envelope decode(String wire) {
        try {
            JsonObject obj = JsonParser.parseString(wire).getAsJsonObject();

            int version = obj.get(F_VERSION).getAsInt();
            if (version > Envelope.CURRENT_VERSION) {
                // A future version we do not know; discard rather than misparse.
                throw new PillarException(
                        "Unrecognized envelope version " + version
                        + " (max known: " + Envelope.CURRENT_VERSION + ")."
                );
            }

            MessageType type = new MessageType(obj.get(F_TYPE).getAsString());
            ServerId senderId = new ServerId(obj.get(F_SENDER).getAsString());
            long sentAt = obj.get(F_SENT_AT).getAsLong();

            Optional<CorrelationId> correlationId = Optional.empty();
            if (obj.has(F_CORRELATION) && !obj.get(F_CORRELATION).isJsonNull()) {
                correlationId = Optional.of(new CorrelationId(obj.get(F_CORRELATION).getAsString()));
            }

            // Serialize the payload subtree back to a string so Envelope stays Gson-free.
            String payload = gson.toJson(obj.get(F_PAYLOAD));

            return new Envelope(version, type, correlationId, senderId, sentAt, payload);
        } catch (PillarException e) {
            throw e;
        } catch (Exception e) {
            throw new PillarException("Failed to decode envelope: " + e.getMessage(), e);
        }
    }

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

    /**
     * Convenience: encode a POJO directly into an envelope payload string.
     * Callers use this to construct the {@code payload} argument for
     * {@link Envelope#oneWay}, {@link Envelope#request}, or {@link Envelope#response}.
     */
    public String encodePayload(Object payloadObject) {
        return gson.toJson(payloadObject);
    }
}
