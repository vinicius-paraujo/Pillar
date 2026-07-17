package br.com.markineo.pillar.redis.messaging;

import br.com.markineo.pillar.api.PillarMessage;
import br.com.markineo.pillar.concurrent.PlatformScheduler;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.task.CorrelationRegistry;
import br.com.markineo.pillar.core.task.HandlerRegistry;
import br.com.markineo.pillar.error.PillarException;
import br.com.markineo.pillar.redis.RedisIntegrationTest;
import br.com.markineo.pillar.redis.RedisKeys;
import br.com.markineo.pillar.redis.presence.FleetView;
import br.com.markineo.pillar.redis.presence.PresenceService;
import br.com.markineo.pillar.redis.transport.JsonEnvelopeCodec;
import br.com.markineo.pillar.redis.transport.RequestSender;
import br.com.markineo.pillar.redis.transport.StreamConsumer;
import br.com.markineo.pillar.redis.transport.StreamPublisher;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.identity.ServerRole;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagingIntegrationTest extends RedisIntegrationTest {

    private final Gson gson = new Gson();
    private final JsonEnvelopeCodec codec = new JsonEnvelopeCodec(gson);

    record TestRequest(String data) {}
    record TestResponse(String ack) {}

    private static final PillarMessage<TestRequest> REQ_TYPE = PillarMessage.of("test:req", TestRequest.class);
    private static final PillarMessage<TestResponse> RES_TYPE = PillarMessage.of("test:res", TestResponse.class);
    private static final PillarMessage<TestRequest> ONE_WAY = PillarMessage.of("test:oneway", TestRequest.class);
    private static final PillarMessage<TestRequest> BROADCAST = PillarMessage.of("test:broadcast", TestRequest.class);

    @Test
    void testRequestHandle() throws Exception {
        ServerId alpha = new ServerId("alpha");
        ServerId beta = new ServerId("beta");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CorrelationRegistry correlationsAlpha = new CorrelationRegistry(scheduler);
        StreamPublisher publisher = new StreamPublisher(connector, codec);
        RequestSender senderAlpha = new RequestSender(publisher, correlationsAlpha);
        
        PlatformScheduler platformScheduler = new PlatformScheduler() {
            @Override public void runSync(Runnable task) { task.run(); }
            @Override public boolean isMainThread() { return false; }
        };

        // Alpha node
        HandlerRegistry alphaHandlers = new HandlerRegistry(correlationsAlpha);
        StreamConsumer alphaConsumer = new StreamConsumer(connector, codec, alpha, executors, logger,
                alphaHandlers.asSink(codec, logger), java.time.Duration.ofMinutes(10), 4, 128);

        PresenceService presenceAlpha = presenceFor(alpha);
        MessagingImpl messagingAlpha = new MessagingImpl(publisher, senderAlpha, alphaHandlers, platformScheduler, codec,
            executors.newBoundedWorkerPool("msg-alpha", 4, 100), alpha, presenceAlpha, logger);

        // Beta node
        CorrelationRegistry correlationsBeta = new CorrelationRegistry(scheduler);
        RequestSender senderBeta = new RequestSender(publisher, correlationsBeta);
        HandlerRegistry betaHandlers = new HandlerRegistry(correlationsBeta);
        StreamConsumer betaConsumer = new StreamConsumer(connector, codec, beta, executors, logger,
                betaHandlers.asSink(codec, logger), java.time.Duration.ofMinutes(10), 4, 128);

        PresenceService presenceBeta = presenceFor(beta);
        MessagingImpl messagingBeta = new MessagingImpl(publisher, senderBeta, betaHandlers, platformScheduler, codec,
            executors.newBoundedWorkerPool("msg-beta", 4, 100), beta, presenceBeta, logger);

        betaConsumer.start();
        alphaConsumer.start();
        presenceAlpha.start();
        presenceBeta.start();
        try {
            await("beta consumer group was never created", () -> groupExists(beta));
            await("alpha consumer group was never created", () -> groupExists(alpha));
            await("alpha never saw beta in the fleet", () -> presenceAlpha.cachedFleet().contains(beta));

            messagingBeta.handle(REQ_TYPE, RES_TYPE, (payload, ctx) -> {
                assertEquals("hello", payload.data());
                return new TestResponse("world");
            });

            CompletableFuture<TestResponse> future = messagingAlpha.request(REQ_TYPE, new TestRequest("hello"), RES_TYPE, "beta");
            TestResponse res = future.get(10, TimeUnit.SECONDS);
            assertEquals("world", res.ack());
        } finally {
            presenceAlpha.close();
            presenceBeta.close();
            betaConsumer.close();
            alphaConsumer.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void testSendListen() throws Exception {
        ServerId alpha = new ServerId("alpha");
        ServerId beta = new ServerId("beta");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CorrelationRegistry correlationsAlpha = new CorrelationRegistry(scheduler);
        StreamPublisher publisher = new StreamPublisher(connector, codec);
        RequestSender senderAlpha = new RequestSender(publisher, correlationsAlpha);
        
        PlatformScheduler platformScheduler = new PlatformScheduler() {
            @Override public void runSync(Runnable task) { task.run(); }
            @Override public boolean isMainThread() { return false; }
        };

        HandlerRegistry alphaHandlers = new HandlerRegistry(correlationsAlpha);
        StreamConsumer alphaConsumer = new StreamConsumer(connector, codec, alpha, executors, logger,
                alphaHandlers.asSink(codec, logger), java.time.Duration.ofMinutes(10), 4, 128);

        PresenceService presenceAlpha = presenceFor(alpha);
        MessagingImpl messagingAlpha = new MessagingImpl(publisher, senderAlpha, alphaHandlers, platformScheduler, codec,
            executors.newBoundedWorkerPool("msg-alpha", 4, 100), alpha, presenceAlpha, logger);

        CorrelationRegistry correlationsBeta = new CorrelationRegistry(scheduler);
        RequestSender senderBeta = new RequestSender(publisher, correlationsBeta);
        HandlerRegistry betaHandlers = new HandlerRegistry(correlationsBeta);
        StreamConsumer betaConsumer = new StreamConsumer(connector, codec, beta, executors, logger,
                betaHandlers.asSink(codec, logger), java.time.Duration.ofMinutes(10), 4, 128);

        PresenceService presenceBeta = presenceFor(beta);
        MessagingImpl messagingBeta = new MessagingImpl(publisher, senderBeta, betaHandlers, platformScheduler, codec,
            executors.newBoundedWorkerPool("msg-beta", 4, 100), beta, presenceBeta, logger);

        betaConsumer.start();
        alphaConsumer.start();
        presenceAlpha.start();
        presenceBeta.start();
        try {
            await("beta consumer group was never created", () -> groupExists(beta));
            await("alpha consumer group was never created", () -> groupExists(alpha));
            await("alpha never saw beta in the fleet", () -> presenceAlpha.cachedFleet().contains(beta));

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<TestRequest> received = new AtomicReference<>();
            
            messagingBeta.listen(ONE_WAY, (payload, ctx) -> {
                received.set(payload);
                latch.countDown();
            });

            messagingAlpha.send(ONE_WAY, new TestRequest("fire-and-forget"), "beta").get(5, TimeUnit.SECONDS);
            
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals("fire-and-forget", received.get().data());
        } finally {
            presenceAlpha.close();
            presenceBeta.close();
            betaConsumer.close();
            alphaConsumer.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void testBroadcastReachesPeersButNotSelf() throws Exception {
        ServerId alpha = new ServerId("alpha");
        ServerId beta = new ServerId("beta");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CorrelationRegistry correlationsAlpha = new CorrelationRegistry(scheduler);
        StreamPublisher publisher = new StreamPublisher(connector, codec);
        RequestSender senderAlpha = new RequestSender(publisher, correlationsAlpha);

        PlatformScheduler platformScheduler = new PlatformScheduler() {
            @Override public void runSync(Runnable task) { task.run(); }
            @Override public boolean isMainThread() { return false; }
        };

        HandlerRegistry alphaHandlers = new HandlerRegistry(correlationsAlpha);
        StreamConsumer alphaConsumer = new StreamConsumer(connector, codec, alpha, executors, logger,
                alphaHandlers.asSink(codec, logger), java.time.Duration.ofMinutes(10), 4, 128);

        PresenceService presenceAlpha = presenceFor(alpha);
        MessagingImpl messagingAlpha = new MessagingImpl(publisher, senderAlpha, alphaHandlers, platformScheduler, codec,
            executors.newBoundedWorkerPool("msg-alpha", 4, 100), alpha, presenceAlpha, logger);

        CorrelationRegistry correlationsBeta = new CorrelationRegistry(scheduler);
        RequestSender senderBeta = new RequestSender(publisher, correlationsBeta);
        HandlerRegistry betaHandlers = new HandlerRegistry(correlationsBeta);
        StreamConsumer betaConsumer = new StreamConsumer(connector, codec, beta, executors, logger,
                betaHandlers.asSink(codec, logger), java.time.Duration.ofMinutes(10), 4, 128);

        PresenceService presenceBeta = presenceFor(beta);
        MessagingImpl messagingBeta = new MessagingImpl(publisher, senderBeta, betaHandlers, platformScheduler, codec,
            executors.newBoundedWorkerPool("msg-beta", 4, 100), beta, presenceBeta, logger);

        betaConsumer.start();
        alphaConsumer.start();
        presenceAlpha.start();
        presenceBeta.start();
        try {
            await("beta consumer group was never created", () -> groupExists(beta));
            await("alpha consumer group was never created", () -> groupExists(alpha));
            await("alpha never saw beta in the fleet", () -> presenceAlpha.cachedFleet().contains(beta));

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<TestRequest> received = new AtomicReference<>();
            AtomicBoolean senderHeardItself = new AtomicBoolean(false);

            messagingBeta.listen(BROADCAST, (payload, ctx) -> {
                received.set(payload);
                latch.countDown();
            });
            messagingAlpha.listen(BROADCAST, (payload, ctx) -> senderHeardItself.set(true));

            messagingAlpha.broadcast(BROADCAST, new TestRequest("to-the-fleet")).get(5, TimeUnit.SECONDS);

            assertTrue(latch.await(5, TimeUnit.SECONDS), "beta never received the broadcast");
            assertEquals("to-the-fleet", received.get().data());

            // Nothing trims an inbox, so an entry in alpha's would mean it published to itself.
            assertEquals(0, inboxLength(alpha), "the sending node must be excluded from its own broadcast");
            assertFalse(senderHeardItself.get());
        } finally {
            presenceAlpha.close();
            presenceBeta.close();
            betaConsumer.close();
            alphaConsumer.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void testBroadcastFailsWhenTheFleetIsUnknown() {
        ServerId alpha = new ServerId("alpha");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CorrelationRegistry correlations = new CorrelationRegistry(scheduler);
        StreamPublisher publisher = new StreamPublisher(connector, codec);
        RequestSender sender = new RequestSender(publisher, correlations);

        PlatformScheduler platformScheduler = new PlatformScheduler() {
            @Override public void runSync(Runnable task) { task.run(); }
            @Override public boolean isMainThread() { return false; }
        };

        // Never started, so no read ever succeeded: the fleet is unknown, not empty.
        PresenceService presence = presenceFor(alpha);
        MessagingImpl messaging = new MessagingImpl(publisher, sender, new HandlerRegistry(correlations),
                platformScheduler, codec, executors.newBoundedWorkerPool("msg-unknown", 4, 100),
                alpha, presence, logger);

        try {
            assertTrue(presence.isStale(), "a presence that never read should report stale");

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> messaging.broadcast(BROADCAST, new TestRequest("into-the-void")).get(5, TimeUnit.SECONDS));

            assertInstanceOf(PillarException.class, failure.getCause());
            assertEquals(0, inboxLength(alpha));
        } finally {
            presence.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void testSendRejectsAnUnknownTargetWhenTheFleetIsKnown() {
        ServerId alpha = new ServerId("alpha");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        PresenceService presence = presenceFor(alpha);
        MessagingImpl messaging = messagingFor(alpha, presence, scheduler, "msg-known");

        presence.start();
        try {
            await("alpha never completed a fleet read", () -> !presence.isStale());

            // Thrown by send() itself, not through the returned future.
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> messaging.send(ONE_WAY, new TestRequest("x"), "ghost"));

            assertTrue(failure.getMessage().contains("ghost"));
            assertEquals(0, inboxLength(new ServerId("ghost")));
        } finally {
            presence.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void testSendSkipsTheTargetCheckWhenTheFleetIsUnknown() throws Exception {
        ServerId alpha = new ServerId("alpha");
        ServerId ghost = new ServerId("ghost");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        // Never started, so no read ever succeeded: an unknown target and one this node
        // has not read yet are indistinguishable, and the send must proceed.
        PresenceService presence = presenceFor(alpha);
        MessagingImpl messaging = messagingFor(alpha, presence, scheduler, "msg-unknown-target");

        try {
            assertTrue(presence.isStale());

            messaging.send(ONE_WAY, new TestRequest("x"), ghost.value()).get(5, TimeUnit.SECONDS);

            // The envelope waits in the target's inbox stream, as the contract claims.
            assertEquals(1, inboxLength(ghost));
        } finally {
            presence.close();
            scheduler.shutdownNow();
        }
    }

    private MessagingImpl messagingFor(ServerId id, PresenceService presence,
                                       ScheduledExecutorService scheduler, String poolName) {
        CorrelationRegistry correlations = new CorrelationRegistry(scheduler);
        StreamPublisher publisher = new StreamPublisher(connector, codec);
        PlatformScheduler platformScheduler = new PlatformScheduler() {
            @Override public void runSync(Runnable task) { task.run(); }
            @Override public boolean isMainThread() { return false; }
        };
        return new MessagingImpl(publisher, new RequestSender(publisher, correlations),
                new HandlerRegistry(correlations), platformScheduler, codec,
                executors.newBoundedWorkerPool(poolName, 4, 100), id, presence, logger);
    }

    private long inboxLength(ServerId node) {
        try (var jedis = connector.getResource()) {
            return jedis.xlen(RedisKeys.inbox(node));
        }
    }

    // Ticks fast so a test never waits a production heartbeat for cachedFleet to populate.
    private PresenceService presenceFor(ServerId id) {
        return new PresenceService(connector, new ServerIdentity(id, new ServerRole("test")),
                new FleetView(connector), executors, logger,
                Duration.ofMillis(100), Duration.ofSeconds(30));
    }

    private boolean groupExists(ServerId node) {
        try (var jedis = connector.getResource()) {
            return jedis.xinfoGroups(RedisKeys.inbox(node)).stream()
                    .anyMatch(group -> "pillar".equals(group.getName()));
        } catch (Exception streamNotCreatedYet) {
            return false;
        }
    }
}
