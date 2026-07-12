package br.com.markineo.pillar.redis.presence;

import com.google.gson.Gson;
import br.com.markineo.pillar.concurrent.PillarExecutors;
import br.com.markineo.pillar.core.health.HealthProvider;
import br.com.markineo.pillar.core.health.HealthSnapshot;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.identity.ServerRole;
import br.com.markineo.pillar.logger.PillarLogger;
import br.com.markineo.pillar.redis.RedisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HealthRegistryIntegrationTest extends RedisIntegrationTest {

    private PillarExecutors executors;
    private PillarLogger logger;
    private Gson gson;

    private PresenceService alphaPresence;
    private HealthService alphaHealth;

    private HealthRegistry proxyRegistry;

    @BeforeEach
    void setUpRegistry() {
        executors = new PillarExecutors(new PillarLogger(LoggerFactory.getLogger("test")));
        logger = new PillarLogger(LoggerFactory.getLogger("test"));
        gson = new Gson();

        // Alpha node (mock game server) starts presence and health
        ServerId alphaId = new ServerId("alpha");
        ServerIdentity alphaIdentity = new ServerIdentity(alphaId, new ServerRole("hub"));
        FleetView alphaFleetView = new FleetView(connector);
        alphaPresence = new PresenceService(connector, alphaIdentity, alphaFleetView, executors, logger, Duration.ofMillis(100), Duration.ofSeconds(30));
        alphaPresence.start();

        HealthProvider stubProvider = () -> new HealthSnapshot(45.0, 1024L, 4096L, 10, 3, 0);
        alphaHealth = new HealthService(connector, alphaId, stubProvider, gson, executors, logger, Duration.ofMillis(100));
        alphaHealth.start();

        // Proxy node starts its own PresenceService and HealthRegistry
        ServerIdentity proxyIdentity = new ServerIdentity(new ServerId("proxy-1"), new ServerRole("proxy"));
        FleetView proxyFleetView = new FleetView(connector);
        PresenceService proxyPresence = new PresenceService(connector, proxyIdentity, proxyFleetView, executors, logger, Duration.ofMillis(100), Duration.ofSeconds(30));
        proxyPresence.start();

        HealthView healthView = new HealthView(connector, gson);
        var reservations = new br.com.markineo.pillar.core.placement.ReservationRegistry(java.time.Clock.systemUTC(), Duration.ofSeconds(5));
        proxyRegistry = new HealthRegistry(proxyPresence, healthView, executors, logger, Duration.ofMillis(100), Duration.ofSeconds(30), reservations);
        proxyRegistry.start();
    }

    @AfterEach
    void tearDownRegistry() {
        if (proxyRegistry != null) proxyRegistry.close();
        if (alphaHealth != null) alphaHealth.close();
        if (alphaPresence != null) alphaPresence.close();
    }

    @Test
    void registryFetchesHealthOfAliveNode() throws InterruptedException {
        // Wait for presence and health loops to tick
        Thread.sleep(300);

        Map<ServerId, HealthSnapshot> snapshot = proxyRegistry.snapshot();
        assertFalse(snapshot.isEmpty(), "Health registry should have fetched the snapshot");

        ServerId alphaId = new ServerId("alpha");
        assertTrue(snapshot.containsKey(alphaId));

        HealthSnapshot alphaSnapshot = snapshot.get(alphaId);
        assertEquals(45.0, alphaSnapshot.mspt());
        assertEquals(10, alphaSnapshot.players());
    }
}
