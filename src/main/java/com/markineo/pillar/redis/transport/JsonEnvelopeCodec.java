package com.markineo.pillar.redis.transport;

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

    @Override
    public Envelope decode(String wire) {
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
