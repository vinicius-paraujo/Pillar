package com.markineo.pillar.redis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

// Gson is a transitive dep of Jedis 5, already relocated in the shadow jar — zero extra weight.
// Short field names (v/t/c/s/ts/p) reduce wire size on high-frequency streams.
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
            // double-encoded string. Fail fast here rather than writing corrupt data.
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
                // Discard rather than misparse: the sender is a newer node we do not understand.
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

            // Guard: absent or null payload would produce the string "null" via gson.toJson,
            // which Envelope accepts (it is a non-blank String). Fail explicitly instead.
            if (!obj.has(F_PAYLOAD) || obj.get(F_PAYLOAD).isJsonNull()) {
                throw new PillarException("Envelope is missing required payload field.");
            }
            // Three-pass overhead: payload is parsed here, re-serialized to String, then
            // parsed again in decodePayload. Acceptable at MVP message volume; revisit if
            // profiling shows this path as hot.
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
        // Class<T> is sufficient for flat objects. When a payload carries a generic
        // collection (e.g. List<Foo>), a TypeToken overload will be needed — deferred
        // until a concrete case appears (YAGNI).
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
