package com.markineo.pillar.redis.transport;

import com.markineo.pillar.redis.transport.JsonEnvelopeCodec;
import com.markineo.pillar.redis.RedisIntegrationTest;
import com.markineo.pillar.redis.RedisKeys;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.task.Envelope;
import com.markineo.pillar.core.task.MessageType;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.StreamEntryID;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StreamTransportIntegrationTest extends RedisIntegrationTest {

    private final Gson gson = new Gson();
    private final JsonEnvelopeCodec codec = new JsonEnvelopeCodec(gson);

    record Ping(String message) {
    }

    @Test
    void publishedMessageIsConsumedAndAcked() throws InterruptedException {
        ServerId self = new ServerId("skyblock-1");
        BlockingQueue<Envelope> received = new LinkedBlockingQueue<>();

        StreamConsumer consumer = new StreamConsumer(connector, codec, self, executors, logger, received::add, java.time.Duration.ofMinutes(10), 4, 128);
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
        try (Jedis jedis = connector.getResource()) {
            return jedis.xinfoGroups(RedisKeys.inbox(self)).stream()
                    .anyMatch(group -> StreamProtocol.CONSUMER_GROUP.equals(group.getName()));
        } catch (JedisException streamNotCreatedYet) {
            return false;
        }
    }

    private long pendingCount(ServerId self) {
        try (Jedis jedis = connector.getResource()) {
            return jedis.xpending(RedisKeys.inbox(self), StreamProtocol.CONSUMER_GROUP).getTotal();
        }
    }

    @Test
    void handlerFailureLeavesEntryInPelAndSuccessAcksIt() throws InterruptedException {
        ServerId self = new ServerId("skyblock-fail");
        BlockingQueue<Envelope> received = new LinkedBlockingQueue<>();

        StreamConsumer consumer = new StreamConsumer(connector, codec, self, executors, logger, envelope -> {
            received.add(envelope);
            if (envelope.type().value().equals("pillar.fail")) {
                throw new RuntimeException("Simulated first-attempt failure");
            }
        }, java.time.Duration.ofMinutes(10), 4, 128);

        consumer.start();
        try {
            await("consumer group was never created", () -> groupExists(self));

            // Publish failing message
            Envelope failRequest = Envelope.request(new MessageType("pillar.fail"), self,
                    codec.encodePayload(new Ping("fail")));
            new StreamPublisher(connector, codec).publish(self, failRequest);

            // Wait for delivery
            Envelope deliveredFail = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(deliveredFail, "failing message was not consumed");

            // Wait a moment for worker to process exception (async)
            Thread.sleep(250);

            // Verify PEL still has the entry
            assertEquals(1, pendingCount(self), "failing message should remain in PEL");

            // Publish successful message
            Envelope okRequest = Envelope.request(new MessageType("pillar.ok"), self,
                    codec.encodePayload(new Ping("ok")));
            new StreamPublisher(connector, codec).publish(self, okRequest);

            // Wait for delivery
            Envelope deliveredOk = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(deliveredOk, "successful message was not consumed");

            // Verify successful message is ACKed (PEL count remains 1 from the failed one)
            await("successful entry was never acked", () -> pendingCount(self) == 1);
        } finally {
            consumer.close();
        }
    }

    @Test
    void orphanedPelEntriesAreDrainedOnRestartAndDeadLetteredAfterLimit() throws InterruptedException {
        ServerId self = new ServerId("skyblock-reclaim");
        BlockingQueue<Envelope> received = new LinkedBlockingQueue<>();
        AtomicInteger invocationCount = new AtomicInteger(0);

        // First consumer start
        StreamConsumer consumer = new StreamConsumer(connector, codec, self, executors, logger, envelope -> {
            received.add(envelope);
            invocationCount.incrementAndGet();
            if (envelope.type().value().equals("pillar.fail")) {
                throw new RuntimeException("Intentional handler failure");
            }
        }, java.time.Duration.ofMinutes(10), 4, 128);

        consumer.start();
        try {
            await("consumer group was never created", () -> groupExists(self));

            // Publish failing message
            Envelope failRequest = Envelope.request(new MessageType("pillar.fail"), self,
                    codec.encodePayload(new Ping("fail")));
            new StreamPublisher(connector, codec).publish(self, failRequest);

            // Wait for delivery (attempt 1 - normal consumption)
            Envelope deliveredFail = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(deliveredFail, "failing message was not consumed on attempt 1");

            // Wait a moment for worker to process exception (async)
            Thread.sleep(250);

            // Verify PEL still has the entry
            assertEquals(1, pendingCount(self), "failing message should remain in PEL");
        } finally {
            consumer.close();
        }

        // Restart 1 (attempt 2 - drained from PEL)
        received.clear();
        consumer = new StreamConsumer(connector, codec, self, executors, logger, envelope -> {
            received.add(envelope);
            invocationCount.incrementAndGet();
            throw new RuntimeException("Intentional handler failure");
        }, java.time.Duration.ofMinutes(10), 4, 128);
        consumer.start();
        try {
            Envelope deliveredFail = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(deliveredFail, "failing message was not consumed on attempt 2");
            Thread.sleep(250);
            assertEquals(1, pendingCount(self), "failing message should remain in PEL");
        } finally {
            consumer.close();
        }

        // Restart 2 (attempt 3 - drained from PEL)
        received.clear();
        consumer = new StreamConsumer(connector, codec, self, executors, logger, envelope -> {
            received.add(envelope);
            invocationCount.incrementAndGet();
            throw new RuntimeException("Intentional handler failure");
        }, java.time.Duration.ofMinutes(10), 4, 128);
        consumer.start();
        try {
            Envelope deliveredFail = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(deliveredFail, "failing message was not consumed on attempt 3");
            Thread.sleep(250);
            assertEquals(1, pendingCount(self), "failing message should remain in PEL");
        } finally {
            consumer.close();
        }

        // Restart 3 (attempt 3 from PEL)
        received.clear();
        consumer = new StreamConsumer(connector, codec, self, executors, logger, envelope -> {
            received.add(envelope);
            invocationCount.incrementAndGet();
            throw new RuntimeException("Intentional handler failure");
        }, java.time.Duration.ofMinutes(10), 4, 128);
        consumer.start();
        try {
            Envelope deliveredFail = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(deliveredFail, "failing message was not consumed on attempt 4");
            Thread.sleep(250);
            assertEquals(1, pendingCount(self), "failing message should remain in PEL");
        } finally {
            consumer.close();
        }

        // Restart 4 (attempt 4 from PEL - dead-lettered!)
        // Since attempts is incremented and checked *before* dispatch,
        // it should hit attempt 4 > MAX_ATTEMPTS (3), log a warning, ACK, and NOT deliver to sink.
        received.clear();
        consumer = new StreamConsumer(connector, codec, self, executors, logger, envelope -> {
            received.add(envelope);
            invocationCount.incrementAndGet();
        }, java.time.Duration.ofMinutes(10), 4, 128);
        consumer.start();
        try {
            // Wait to ensure processing is done
            Thread.sleep(1000);

            // Should NOT be delivered
            assertEquals(0, received.size(), "dead-lettered message should not be delivered");

            // Should be removed from PEL
            assertEquals(0, pendingCount(self), "dead-lettered message should be removed from PEL");

            // Total invocations should be exactly 4 (Initial + Restart 1 + Restart 2 + Restart 3)
            assertEquals(4, invocationCount.get(), "Handler should have been invoked exactly 4 times");
        } finally {
            consumer.close();
        }
    }

    @Test
    void duplicateMessagesAreIgnoredAndAcked() throws InterruptedException {
        ServerId self = new ServerId("skyblock-dedup");
        BlockingQueue<Envelope> received = new LinkedBlockingQueue<>();
        AtomicInteger invocationCount = new AtomicInteger(0);

        try (Jedis jedis = connector.getResource()) {
            jedis.xgroupCreate(RedisKeys.inbox(self), StreamProtocol.CONSUMER_GROUP, StreamEntryID.XGROUP_LAST_ENTRY, true);
            
            // 1. Manually add a message with a specific ID
            StreamEntryID specificId = new StreamEntryID("1000000000000-0");
            Envelope request = Envelope.request(new MessageType("pillar.ping"), self,
                    codec.encodePayload(new Ping("dedup-test")));
            
            jedis.xadd(RedisKeys.inbox(self), specificId, java.util.Map.of(StreamProtocol.FIELD_DATA, codec.encode(request)));
            
            // 2. Mark it as already processed (dedup key)
            jedis.setex(RedisKeys.dedup(self, specificId.toString()), 600, "DONE");
        } catch (JedisException ignored) { }

        StreamConsumer consumer = new StreamConsumer(connector, codec, self, executors, logger, envelope -> {
            invocationCount.incrementAndGet();
            received.add(envelope);
        }, java.time.Duration.ofMinutes(10), 4, 128);
        
        consumer.start();
        try {
            // Wait a bit to ensure consumer reads it
            Thread.sleep(1000);

            // 3. Assert handler was NEVER invoked
            assertEquals(0, invocationCount.get(), "Handler should not be invoked for a duplicated message");
            
            // 4. Assert message was ACKed (removed from PEL)
            assertEquals(0, pendingCount(self), "Duplicate message should be ACKed");
        } finally {
            consumer.close();
        }
    }
}
