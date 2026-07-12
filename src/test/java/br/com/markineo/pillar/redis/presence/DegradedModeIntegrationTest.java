package br.com.markineo.pillar.redis.presence;

import br.com.markineo.pillar.concurrent.PillarExecutors;
import br.com.markineo.pillar.config.RedisSettings;
import br.com.markineo.pillar.core.fleet.FleetSnapshot;
import br.com.markineo.pillar.core.health.HealthSnapshot;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.identity.ServerRole;
import br.com.markineo.pillar.core.placement.EligibilityFilter;
import br.com.markineo.pillar.core.placement.HardCaps;
import br.com.markineo.pillar.core.placement.NoEligibleNodeException;
import br.com.markineo.pillar.core.placement.PlacementSelector;
import br.com.markineo.pillar.core.placement.PlacementService;
import br.com.markineo.pillar.core.placement.ReservationRegistry;
import br.com.markineo.pillar.logger.PillarLogger;
import br.com.markineo.pillar.redis.lifecycle.RedisConnector;
import com.google.gson.Gson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;

import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

@Testcontainers(disabledWithoutDocker = true)
class DegradedModeIntegrationTest {

    private static final int REDIS_PORT = 6379;

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(REDIS_PORT);

    private PillarLogger logger;
    private PillarExecutors executors;
    private RedisConnector connector;

    private PresenceService proxyPresence;
    private HealthRegistry healthRegistry;
    private PlacementService placement;

    private static final Duration STALENESS_WINDOW = Duration.ofSeconds(2);
    private static final Duration TICK_INTERVAL = Duration.ofMillis(100);

    @BeforeEach
    void setUp() {
        logger = new PillarLogger(LoggerFactory.getLogger("test"));
        executors = new PillarExecutors(logger);

        RedisSettings settings = new RedisSettings(
                REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT), "", 16, Duration.ofMillis(500), STALENESS_WINDOW);
        connector = new RedisConnector(settings, executors, logger);
        connector.start();
        awaitReady();

        try (Jedis jedis = connector.getResource()) {
            jedis.flushAll();
        }

        ServerId proxyId = new ServerId("proxy-1");
        
        Gson gson = new Gson();
        HealthView healthView = new HealthView(connector, gson);
        FleetView fleetView = new FleetView(connector);
        
        proxyPresence = new PresenceService(connector, new ServerIdentity(proxyId, new ServerRole("proxy")), fleetView, executors, logger, TICK_INTERVAL, STALENESS_WINDOW);
        proxyPresence.start();
        ReservationRegistry reservations = new ReservationRegistry(Clock.systemUTC(), Duration.ofSeconds(5));
        
        healthRegistry = new HealthRegistry(proxyPresence, healthView, executors, logger, TICK_INTERVAL, STALENESS_WINDOW, reservations);
        healthRegistry.start();

        EligibilityFilter filter = new EligibilityFilter(new HardCaps(100, 0.9, 50.0));
        PlacementSelector selector = new PlacementSelector(reservations);
        placement = new PlacementService(filter, selector, reservations);
    }

    @AfterEach
    void tearDown() {
        if (healthRegistry != null) healthRegistry.close();
        if (proxyPresence != null) proxyPresence.close();
        if (connector != null) connector.close();
        
        // Ensure unpaused if test failed mid-pause
        try {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        } catch (Exception ignored) {}
    }

    @Test
    void testDegradedModeLifecycle() throws InterruptedException {
        ServerIdentity node = new ServerIdentity(new ServerId("lobby-1"), new ServerRole("lobby"));
        
        // 1. Publish healthy node
        new HeartbeatPublisher(connector, node).publish();
        Gson gson = new Gson();
        try (Jedis jedis = connector.getResource()) {
            jedis.set("pillar:health:lobby-1", gson.toJson(new HealthSnapshot(20.0, 1024, 2048, 5, 0, 0)));
        }

        // Wait for proxy to cache it
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            boolean inFleet = proxyPresence.cachedFleet().contains(node.id());
            boolean hasHealth = healthRegistry.snapshot().containsKey(node.id());
            if (inFleet && hasHealth) {
                break;
            }
            Thread.sleep(50);
        }

        // 2. Verify placement succeeds initially
        assertDoesNotThrow(() -> placePlayer("lobby"));

        // 3. Drop Redis (simulate outage)
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();

        // 4. Verify placement STILL SUCCEEDS during the staleness window
        Thread.sleep(300); // 300ms < 2s window
        assertDoesNotThrow(() -> placePlayer("lobby"), "Placement must succeed within staleness window despite Redis being down");

        // 5. Wait for window to expire and cache to clear
        long clearDeadline = System.nanoTime() + STALENESS_WINDOW.plusSeconds(4).toNanos();
        while (System.nanoTime() < clearDeadline) {
            if (proxyPresence.cachedFleet().members().isEmpty() && healthRegistry.snapshot().isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }

        // 6. Verify placement now FAILS
        assertThrows(NoEligibleNodeException.class, () -> placePlayer("lobby"), "Placement must fail after staleness window expires");

        // 7. Recover Redis
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();

        // 8. Wait for recovery
        awaitReady();
        Thread.sleep(300);

        // 9. Re-publish heartbeat (node's own heartbeat loop would do this)
        new HeartbeatPublisher(connector, node).publish();
        
        long recoveryDeadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < recoveryDeadline) {
            boolean inFleet = proxyPresence.cachedFleet().contains(node.id());
            boolean hasHealth = healthRegistry.snapshot().containsKey(node.id());
            if (inFleet && hasHealth) {
                break;
            }
            Thread.sleep(50);
        }

        // 10. Verify placement SUCCEEDS again
        assertDoesNotThrow(() -> placePlayer("lobby"), "Placement must succeed after Redis recovery");
    }

    private void placePlayer(String role) throws NoEligibleNodeException {
        placement.place(new ServerRole(role), proxyPresence.cachedFleet(), id -> healthRegistry.snapshot().get(id));
    }

    private void awaitReady() {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (connector.isReady()) return;
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
        fail("Redis connection never became READY");
    }
}
