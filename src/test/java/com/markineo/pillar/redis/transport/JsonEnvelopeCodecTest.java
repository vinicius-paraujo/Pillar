package com.markineo.pillar.redis.transport;

import com.markineo.pillar.redis.transport.JsonEnvelopeCodec;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.task.CorrelationId;
import com.markineo.pillar.core.task.Envelope;
import com.markineo.pillar.core.task.MessageType;
import com.markineo.pillar.error.PillarException;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonEnvelopeCodecTest {

    private final Gson gson = new Gson();
    private final JsonEnvelopeCodec codec = new JsonEnvelopeCodec(gson);

    record Ping(String message) {
    }

    @Test
    void roundTripsARequestEnvelope() {
        String payload = codec.encodePayload(new Ping("hello"));
        Envelope original = Envelope.request(new MessageType("pillar.ping"), new ServerId("skyblock-1"), payload);

        Envelope decoded = codec.decode(codec.encode(original));

        assertEquals(original.type(), decoded.type());
        assertEquals(original.senderId(), decoded.senderId());
        assertEquals(original.correlationId(), decoded.correlationId());
        assertEquals("hello", codec.decodePayload(decoded, Ping.class).message());
    }

    @Test
    void oneWayEnvelopeHasNoCorrelationId() {
        Envelope oneWay = Envelope.oneWay(new MessageType("pillar.event"), new ServerId("hub-1"),
                codec.encodePayload(new Ping("x")));

        assertTrue(codec.decode(codec.encode(oneWay)).correlationId().isEmpty());
    }

    @Test
    void preservesCorrelationIdAcrossTheWire() {
        CorrelationId id = CorrelationId.generate();
        Envelope response = Envelope.response(new MessageType("pillar.pong"), id, new ServerId("skyblock-1"),
                codec.encodePayload(new Ping("pong")));

        assertEquals(id, codec.decode(codec.encode(response)).correlationId().orElseThrow());
    }

    @Test
    void rejectsAFutureVersion() {
        String futureVersion = codec.encode(Envelope.oneWay(new MessageType("pillar.ping"),
                new ServerId("skyblock-1"), codec.encodePayload(new Ping("x"))))
                .replaceFirst("\"v\":2", "\"v\":999");

        assertThrows(PillarException.class, () -> codec.decode(futureVersion));
    }

    @Test
    void rejectsMalformedWire() {
        assertThrows(PillarException.class, () -> codec.decode("not json"));
    }
}
