package com.markineo.pillar.redis.transport;

import com.markineo.pillar.redis.transport.JsonEnvelopeCodec;
import com.markineo.pillar.redis.RedisIntegrationTest;
import com.markineo.pillar.redis.RedisKeys;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.task.CorrelationRegistry;
import com.markineo.pillar.core.task.Envelope;
import com.markineo.pillar.core.task.HandlerRegistry;
import com.markineo.pillar.core.task.PillarMessageTypes;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PingPongIntegrationTest extends RedisIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final JsonEnvelopeCodec codec = new JsonEnvelopeCodec();

    @Test
    void pingRoundTripsAndPongIsDeliveredToTheFuture() throws Exception {
        ServerId alpha = new ServerId("alpha");
        ServerId beta = new ServerId("beta");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CorrelationRegistry correlations = new CorrelationRegistry(scheduler);
        StreamPublisher publisher = new StreamPublisher(connector, codec);

        // Beta: handles pings and responds.
        HandlerRegistry betaHandlers = new HandlerRegistry(new CorrelationRegistry(scheduler));
        betaHandlers.register(new PingHandler(beta, publisher, logger));
        StreamConsumer betaConsumer = new StreamConsumer(connector, codec, beta, executors, logger,
                betaHandlers.asSink(codec, logger), java.time.Duration.ofMinutes(10), 4, 128);

        // Alpha: routes pongs back to the pending future.
        HandlerRegistry alphaHandlers = new HandlerRegistry(correlations);
        StreamConsumer alphaConsumer = new StreamConsumer(connector, codec, alpha, executors, logger,
                alphaHandlers.asSink(codec, logger), java.time.Duration.ofMinutes(10), 4, 128);

        betaConsumer.start();
        alphaConsumer.start();
        try {
            // Both groups must exist before the ping is published; XGROUP_LAST_ENTRY ($)
            // means messages published before the group is created are invisible to it.
            await("beta consumer group was never created", () -> groupExists(beta));
            await("alpha consumer group was never created", () -> groupExists(alpha));

            RequestSender sender = new RequestSender(publisher, correlations);
            Envelope ping = Envelope.request(PillarMessageTypes.PING, alpha, "{}");
            CompletableFuture<Envelope> pong = sender.send(beta, ping, TIMEOUT);

            Envelope response = pong.get(10, TimeUnit.SECONDS);
            assertNotNull(response);
            assertEquals(PillarMessageTypes.PONG, response.type());
            assertEquals(ping.correlationId(), response.correlationId());
        } finally {
            betaConsumer.close();
            alphaConsumer.close();
            scheduler.shutdownNow();
        }
    }

    private boolean groupExists(ServerId node) {
        try (var jedis = connector.pool().getResource()) {
            return jedis.xinfoGroups(RedisKeys.inbox(node)).stream()
                    .anyMatch(group -> StreamProtocol.CONSUMER_GROUP.equals(group.getName()));
        } catch (Exception streamNotCreatedYet) {
            return false;
        }
    }
}
