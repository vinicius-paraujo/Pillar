package com.markineo.pillar.redis;

import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.task.Envelope;
import com.markineo.pillar.core.task.MessageType;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisException;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StreamTransportIntegrationTest extends RedisIntegrationTest {

    private final JsonEnvelopeCodec codec = new JsonEnvelopeCodec();

    record Ping(String message) {
    }

    @Test
    void publishedMessageIsConsumedAndAcked() throws InterruptedException {
        ServerId self = new ServerId("skyblock-1");
        BlockingQueue<Envelope> received = new LinkedBlockingQueue<>();

        StreamConsumer consumer = new StreamConsumer(connector, codec, self, executors, logger, received::add);
        consumer.start();
        try {
            await("consumer group was never created", () -> groupExists(self));

            Envelope request = Envelope.request(new MessageType("pillar.ping"), self,
                    codec.encodePayload(new Ping("hi")));
            new StreamPublisher(connector, codec).publish(self, request);

            Envelope delivered = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(delivered, "message was not consumed");
            assertEquals(request.correlationId(), delivered.correlationId());
            assertEquals("hi", codec.decodePayload(delivered, Ping.class).message());

            await("processed entry was never acked", () -> pendingCount(self) == 0);
        } finally {
            consumer.close();
        }
    }

    private boolean groupExists(ServerId self) {
        try (Jedis jedis = connector.pool().getResource()) {
            return jedis.xinfoGroups(RedisKeys.inbox(self)).stream()
                    .anyMatch(group -> StreamProtocol.CONSUMER_GROUP.equals(group.getName()));
        } catch (JedisException streamNotCreatedYet) {
            return false;
        }
    }

    private long pendingCount(ServerId self) {
        try (Jedis jedis = connector.pool().getResource()) {
            return jedis.xpending(RedisKeys.inbox(self), StreamProtocol.CONSUMER_GROUP).getTotal();
        }
    }
}
