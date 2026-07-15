package br.com.markineo.pillar.redis.routing;

import br.com.markineo.pillar.api.Routing;
import br.com.markineo.pillar.concurrent.PillarExecutors;
import br.com.markineo.pillar.concurrent.PlatformScheduler;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.identity.ServerRole;
import br.com.markineo.pillar.core.placement.RouteOutcome;
import br.com.markineo.pillar.core.placement.RoutePlayerRequest;
import br.com.markineo.pillar.core.placement.RoutePlayerResponse;
import br.com.markineo.pillar.core.task.CorrelationRegistry;
import br.com.markineo.pillar.core.task.Envelope;
import br.com.markineo.pillar.core.task.EnvelopeCodec;
import br.com.markineo.pillar.core.task.EnvelopeHandler;
import br.com.markineo.pillar.core.task.HandlerRegistry;
import br.com.markineo.pillar.core.task.MessageType;
import br.com.markineo.pillar.core.task.PillarMessageTypes;
import br.com.markineo.pillar.logger.PillarLogger;
import br.com.markineo.pillar.redis.RedisIntegrationTest;
import br.com.markineo.pillar.redis.transport.JsonEnvelopeCodec;
import br.com.markineo.pillar.redis.presence.FleetView;
import br.com.markineo.pillar.redis.presence.PresenceService;
import br.com.markineo.pillar.redis.transport.RequestSender;
import br.com.markineo.pillar.redis.transport.StreamConsumer;
import br.com.markineo.pillar.redis.transport.StreamPublisher;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoutingIntegrationTest extends RedisIntegrationTest {

    private PillarExecutors executors;
    private ExecutorService pool;
    private ScheduledExecutorService timeoutScheduler;
    private PresenceService proxyPresence;
    private Routing routing;
    private StreamConsumer proxyConsumer;
    
    // Config
    private final ServerId proxyId = new ServerId("proxy-1");
    private final ServerId backendId = new ServerId("backend-1");
    private final EnvelopeCodec codec = new JsonEnvelopeCodec(new GsonBuilder().create());
    private final AtomicReference<RouteOutcome> mockOutcome = new AtomicReference<>(RouteOutcome.SUCCESS);

    @BeforeEach
    void setUpLocal() throws InterruptedException {
        PillarLogger logger = new PillarLogger(LoggerFactory.getLogger(RoutingIntegrationTest.class));
        executors = new PillarExecutors(logger);
        pool = executors.newBoundedWorkerPool("test-pool", 4, 100);
        timeoutScheduler = executors.newSingleThreadScheduled("timeout-scheduler");
        
        PlatformScheduler scheduler = new PlatformScheduler() {
            @Override public void runSync(Runnable task) { task.run(); }
            @Override public boolean isMainThread() { return false; }
        };

        FleetView fleetView = new FleetView(connector);
        
        // Start proxy presence
        ServerIdentity proxyIdentity = new ServerIdentity(proxyId, new ServerRole("proxy"));
        proxyPresence = new PresenceService(connector, proxyIdentity, fleetView, executors, logger, Duration.ofSeconds(1), Duration.ofSeconds(3));
        proxyPresence.start();
        
        // Wait for proxy to appear in fleet view
        Thread.sleep(1500);

        // Setup proxy's mock handler for ROUTE_PLAYER
        CorrelationRegistry proxyCorrelations = new CorrelationRegistry(timeoutScheduler);
        HandlerRegistry proxyHandlers = new HandlerRegistry(proxyCorrelations);
        StreamPublisher proxyPublisher = new StreamPublisher(connector, codec);
        
        proxyHandlers.register(new EnvelopeHandler() {
            @Override public MessageType type() { return PillarMessageTypes.ROUTE_PLAYER; }
            @Override public void handle(Envelope envelope, EnvelopeCodec c) {
                // If the outcome is configured to be timeout, we just don't reply.
                if (mockOutcome.get() != null) {
                    try {
                        RoutePlayerResponse response = new RoutePlayerResponse(mockOutcome.get());
                        String json = codec.encodePayload(response);
                        Envelope responseEnv = Envelope.response(type(), envelope.correlationId().get(), proxyId, json);
                        proxyPublisher.publish(envelope.senderId(), responseEnv);
                    } catch (Exception e) {}
                }
            }
        });
        
        proxyConsumer = new StreamConsumer(connector, codec, proxyId, executors, logger, proxyHandlers.asSink(codec, logger), Duration.ofSeconds(10), 4, 100);
        proxyConsumer.start();

        // Setup backend routing instance
        CorrelationRegistry backendCorrelations = new CorrelationRegistry(timeoutScheduler);
        RequestSender requestSender = new RequestSender(new StreamPublisher(connector, codec), backendCorrelations);
        
        // Start a consumer for the backend just to receive the correlation replies
        HandlerRegistry backendHandlers = new HandlerRegistry(backendCorrelations);
        StreamConsumer backendConsumer = new StreamConsumer(connector, codec, backendId, executors, logger, backendHandlers.asSink(codec, logger), Duration.ofSeconds(10), 4, 100);
        backendConsumer.start();

        routing = new RoutingImpl(requestSender, fleetView, codec, scheduler, pool, backendId, "proxy");
    }

    @AfterEach
    void tearDownLocal() {
        if (proxyPresence != null) proxyPresence.close();
        if (proxyConsumer != null) proxyConsumer.close();
        if (pool != null) pool.shutdownNow();
        if (timeoutScheduler != null) timeoutScheduler.shutdownNow();
    }

    @Test
    void testMoveToRoleSuccess() {
        mockOutcome.set(RouteOutcome.SUCCESS);
        RouteOutcome outcome = routing.moveToRole(UUID.randomUUID(), "survival").join();
        assertEquals(RouteOutcome.SUCCESS, outcome);
    }

    @Test
    void testMoveToRoleProxyUnreachableByTimeout() {
        mockOutcome.set(null); // Force timeout by not replying
        RouteOutcome outcome = routing.moveToRole(UUID.randomUUID(), "survival").join();
        assertEquals(RouteOutcome.PROXY_UNREACHABLE, outcome);
    }
}
