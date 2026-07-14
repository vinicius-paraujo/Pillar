package br.com.markineo.pillar.redis.messaging;

import br.com.markineo.pillar.api.PillarMessage;
import br.com.markineo.pillar.concurrent.PlatformScheduler;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.task.CorrelationRegistry;
import br.com.markineo.pillar.core.task.HandlerRegistry;
import br.com.markineo.pillar.redis.RedisIntegrationTest;
import br.com.markineo.pillar.redis.RedisKeys;
import br.com.markineo.pillar.redis.presence.FleetView;
import br.com.markineo.pillar.redis.transport.JsonEnvelopeCodec;
import br.com.markineo.pillar.redis.transport.RequestSender;
import br.com.markineo.pillar.redis.transport.StreamConsumer;
import br.com.markineo.pillar.redis.transport.StreamPublisher;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagingIntegrationTest extends RedisIntegrationTest {

    private final Gson gson = new Gson();
    private final JsonEnvelopeCodec codec = new JsonEnvelopeCodec(gson);

    record TestRequest(String data) {}
    record TestResponse(String ack) {}

    private static final PillarMessage<TestRequest> REQ_TYPE = PillarMessage.of("test:req", TestRequest.class);
    private static final PillarMessage<TestResponse> RES_TYPE = PillarMessage.of("test:res", TestResponse.class);
    private static final PillarMessage<TestRequest> ONE_WAY = PillarMessage.of("test:oneway", TestRequest.class);

    @Test
    void testRequestHandle() throws Exception {
        ServerId alpha = new ServerId("alpha");
        ServerId beta = new ServerId("beta");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CorrelationRegistry correlationsAlpha = new CorrelationRegistry(scheduler);
        StreamPublisher publisher = new StreamPublisher(connector, codec);
        RequestSender senderAlpha = new RequestSender(publisher, correlationsAlpha);
        
        PlatformScheduler platformScheduler = task -> task.run();

        // Alpha node
        HandlerRegistry alphaHandlers = new HandlerRegistry(correlationsAlpha);
        StreamConsumer alphaConsumer = new StreamConsumer(connector, codec, alpha, executors, logger,
                alphaHandlers.asSink(codec, logger), java.time.Duration.ofMinutes(10), 4, 128);

        MessagingImpl messagingAlpha = new MessagingImpl(publisher, senderAlpha, alphaHandlers, platformScheduler, codec, 
            executors.newBoundedWorkerPool("msg-alpha", 4, 100), alpha, new FleetView(connector), logger);

        // Beta node
        CorrelationRegistry correlationsBeta = new CorrelationRegistry(scheduler);
        RequestSender senderBeta = new RequestSender(publisher, correlationsBeta);
        HandlerRegistry betaHandlers = new HandlerRegistry(correlationsBeta);
        StreamConsumer betaConsumer = new StreamConsumer(connector, codec, beta, executors, logger,
                betaHandlers.asSink(codec, logger), java.time.Duration.ofMinutes(10), 4, 128);

        MessagingImpl messagingBeta = new MessagingImpl(publisher, senderBeta, betaHandlers, platformScheduler, codec, 
            executors.newBoundedWorkerPool("msg-beta", 4, 100), beta, new FleetView(connector), logger);

        betaConsumer.start();
        alphaConsumer.start();
        try {
            await("beta consumer group was never created", () -> groupExists(beta));
            await("alpha consumer group was never created", () -> groupExists(alpha));

            messagingBeta.handle(REQ_TYPE, RES_TYPE, (payload, ctx) -> {
                assertEquals("hello", payload.data());
                return new TestResponse("world");
            });

            CompletableFuture<TestResponse> future = messagingAlpha.request(REQ_TYPE, new TestRequest("hello"), RES_TYPE, "beta");
            TestResponse res = future.get(10, TimeUnit.SECONDS);
            assertEquals("world", res.ack());
        } finally {
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
        
        PlatformScheduler platformScheduler = task -> task.run();

        HandlerRegistry alphaHandlers = new HandlerRegistry(correlationsAlpha);
        StreamConsumer alphaConsumer = new StreamConsumer(connector, codec, alpha, executors, logger,
                alphaHandlers.asSink(codec, logger), java.time.Duration.ofMinutes(10), 4, 128);

        MessagingImpl messagingAlpha = new MessagingImpl(publisher, senderAlpha, alphaHandlers, platformScheduler, codec, 
            executors.newBoundedWorkerPool("msg-alpha", 4, 100), alpha, new FleetView(connector), logger);

        CorrelationRegistry correlationsBeta = new CorrelationRegistry(scheduler);
        RequestSender senderBeta = new RequestSender(publisher, correlationsBeta);
        HandlerRegistry betaHandlers = new HandlerRegistry(correlationsBeta);
        StreamConsumer betaConsumer = new StreamConsumer(connector, codec, beta, executors, logger,
                betaHandlers.asSink(codec, logger), java.time.Duration.ofMinutes(10), 4, 128);

        MessagingImpl messagingBeta = new MessagingImpl(publisher, senderBeta, betaHandlers, platformScheduler, codec, 
            executors.newBoundedWorkerPool("msg-beta", 4, 100), beta, new FleetView(connector), logger);

        betaConsumer.start();
        alphaConsumer.start();
        try {
            await("beta consumer group was never created", () -> groupExists(beta));
            await("alpha consumer group was never created", () -> groupExists(alpha));

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
            betaConsumer.close();
            alphaConsumer.close();
            scheduler.shutdownNow();
        }
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
