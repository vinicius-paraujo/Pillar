package com.markineo.pillar.redis;

import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.task.Envelope;
import com.markineo.pillar.core.task.EnvelopeCodec;
import com.markineo.pillar.error.PillarException;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisException;

import java.util.Map;

public final class StreamPublisher {

    private final RedisConnector connector;
    private final EnvelopeCodec codec;

    public StreamPublisher(RedisConnector connector, EnvelopeCodec codec) {
        this.connector = connector;
        this.codec = codec;
    }

    public void publish(ServerId destination, Envelope envelope) {
        String wire = codec.encode(envelope);
        try (Jedis jedis = connector.pool().getResource()) {
            jedis.xadd(RedisKeys.inbox(destination), StreamEntryID.NEW_ENTRY,
                    Map.of(StreamProtocol.FIELD_DATA, wire));
        } catch (JedisException e) {
            throw new PillarException("Failed to publish to inbox of " + destination.value() + ".", e);
        }
    }
}
