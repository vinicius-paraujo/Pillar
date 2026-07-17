package br.com.markineo.pillar.redis.routing;

import br.com.markineo.pillar.api.Routing;
import br.com.markineo.pillar.concurrent.PillarExecutors;
import br.com.markineo.pillar.concurrent.PlatformScheduler;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.identity.ServerRole;
import br.com.markineo.pillar.api.routing.RouteOutcome;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingIntegrationTest extends RedisIntegrationTest {

    private PillarExecutors executors;
    private ExecutorService pool;
    private ScheduledExecutorService timeoutScheduler;
    private PresenceService proxyPresence;
    private PresenceService backendPresence;
    private Routing routing;
    private StreamConsumer proxyConsumer;
    private RequestSender requestSender;
    private PlatformScheduler platformScheduler;
    
    // Config
    private final ServerId proxyId = new ServerId("proxy-1");
    private final ServerId backendId = new ServerId("backend-1");
    private final EnvelopeCodec codec = new JsonEnvelopeCodec(new GsonBuilder().create());
    private final AtomicReference<RouteOutcome> mockOutcome = new AtomicReference<>(RouteOutcome.SUCCESS);
    // Lets a test put a name on the wire that RouteOutcome does not define, standing in for
    // a proxy running a newer Pillar.
    private final AtomicReference<String> rawOutcome = new AtomicReference<>();

    @BeforeEach
    void setUpLocal() throws InterruptedException {
        PillarLogger logger = new PillarLogger(LoggerFactory.getLogger(RoutingIntegrationTest.class));
        executors = new PillarExecutors(logger);
        pool = executors.newBoundedWorkerPool("test-pool", 4, 100);
        timeoutScheduler = executors.newSingleThreadScheduled("timeout-scheduler");
        
        PlatformScheduler scheduler = platformScheduler = new PlatformScheduler() {
            @Override public void runSync(Runnable task) { task.run(); }
            @Override public boolean isMainThread() { return false; }
        };

        FleetView fleetView = new FleetView(connector);
        
        // Start proxy presence
        ServerIdentity proxyIdentity = new ServerIdentity(proxyId, new ServerRole("proxy"));
        proxyPresence = new PresenceService(connector, proxyIdentity, fleetView, executors, logger, Duration.ofSeconds(1), Duration.ofSeconds(3));
        proxyPresence.start();

        // The backend resolves the proxy through its own cached fleet, so it carries presence too.
        ServerIdentity backendIdentity = new ServerIdentity(backendId, new ServerRole("backend"));
        backendPresence = new PresenceService(connector, backendIdentity, fleetView, executors, logger, Duration.ofSeconds(1), Duration.ofSeconds(3));
        backendPresence.start();

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
                if (mockOutcome.get() != null || rawOutcome.get() != null) {
                    try {
                        RoutePlayerResponse response = rawOutcome.get() != null
                                ? new RoutePlayerResponse(rawOutcome.get())
                                : RoutePlayerResponse.of(mockOutcome.get());
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
        requestSender = new RequestSender(new StreamPublisher(connector, codec), backendCorrelations);
        
        // Start a consumer for the backend just to receive the correlation replies
        HandlerRegistry backendHandlers = new HandlerRegistry(backendCorrelations);
        StreamConsumer backendConsumer = new StreamConsumer(connector, codec, backendId, executors, logger, backendHandlers.asSink(codec, logger), Duration.ofSeconds(10), 4, 100);
        backendConsumer.start();

        routing = new RoutingImpl(requestSender, backendPresence, codec, scheduler, pool, backendId, "proxy", logger);
    }

    @AfterEach
    void tearDownLocal() {
        if (proxyPresence != null) proxyPresence.close();
        if (backendPresence != null) backendPresence.close();
        if (proxyConsumer != null) proxyConsumer.close();
        if (pool != null) pool.shutdownNow();
        if (timeoutScheduler != null) timeoutScheduler.shutdownNow();
    }

    @Test
    void testMoveToRoleSuccess() {
        mockOutcome.set(RouteOutcome.SUCCESS);
        RouteOutcome outcome = routing.moveToRole(UUID.randomUUID(), "survival").join();

        // assertSame, not assertEquals: the constant crosses Redis as a name and must come
        // back as the same instance, or == silently stops working for every consumer.
        assertSame(RouteOutcome.SUCCESS, outcome);
        assertTrue(outcome.isSuccess());
    }

    @Test
    void testMoveToRoleActuationFailedByTimeout() {
        mockOutcome.set(null); // Force timeout by not replying
        RouteOutcome outcome = routing.moveToRole(UUID.randomUUID(), "survival").join();

        assertSame(RouteOutcome.ACTUATION_FAILED, outcome);
        assertFalse(outcome.isSuccess());
        assertTrue(outcome.isTransient());
    }

    @Test
    void testRouteFailsWhenNoFleetReadSucceededInTheWindow() {
        mockOutcome.set(RouteOutcome.SUCCESS);

        // The proxy is live and reachable, and the cache still names it — this reproduces the
        // beat after the window closes but before the presence tick blanks the cache. A
        // retained name is a guess, so the route must refuse rather than ride it.
        PresenceService pastTheWindow = new PresenceService(connector,
                new ServerIdentity(backendId, new ServerRole("backend")),
                new FleetView(connector), executors, logger,
                Duration.ofMillis(100), Duration.ofSeconds(3)) {
            @Override
            public boolean isStale() {
                return true;
            }
        };
        pastTheWindow.start();
        Routing blind = new RoutingImpl(requestSender, pastTheWindow, codec, platformScheduler, pool,
                backendId, "proxy", logger);
        try {
            await("the cached fleet never named the proxy",
                    () -> pastTheWindow.cachedFleet().contains(proxyId));

            RouteOutcome outcome = blind.moveToRole(UUID.randomUUID(), "survival").join();

            assertSame(RouteOutcome.ACTUATION_FAILED, outcome);
            assertFalse(outcome.isSuccess());
        } finally {
            pastTheWindow.close();
        }
    }

    @Test
    void testOutcomeFromANewerPillarDegradesInsteadOfFailing() {
        rawOutcome.set("SOME_OUTCOME_FROM_THE_FUTURE");
        RouteOutcome outcome = routing.moveToRole(UUID.randomUUID(), "survival").join();

        // The consumer's property branching still answers sanely for a name this
        // version never heard of.
        assertSame(RouteOutcome.ACTUATION_FAILED, outcome);
        assertFalse(outcome.isSuccess());
    }
}
