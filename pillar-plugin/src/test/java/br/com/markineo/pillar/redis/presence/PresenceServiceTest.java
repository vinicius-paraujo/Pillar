package br.com.markineo.pillar.redis.presence;

import br.com.markineo.pillar.concurrent.PillarExecutors;
import br.com.markineo.pillar.config.RedisSettings;
import br.com.markineo.pillar.core.fleet.FleetSnapshot;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.identity.ServerRole;
import br.com.markineo.pillar.logger.PillarLogger;
import br.com.markineo.pillar.redis.lifecycle.RedisConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceServiceTest {

    private PresenceService presence;
    private PillarExecutors executors;
    private PillarLogger logger;
    
    private Optional<FleetSnapshot> stubFleet = Optional.empty();

    private static final Duration STALENESS_WINDOW = Duration.ofMillis(500);

    @BeforeEach
    void setUp() {
        logger = new PillarLogger(LoggerFactory.getLogger("test"));
        executors = new PillarExecutors(logger);

        ServerIdentity identity = new ServerIdentity(new ServerId("proxy-1"), new ServerRole("proxy"));
        
        RedisSettings settings = new RedisSettings("localhost", 6379, "", 1, Duration.ofMillis(100), STALENESS_WINDOW, 30000L);
        RedisConnector fakeConnector = new RedisConnector(settings, executors, logger) {
            @Override
            public boolean isReady() {
                return false;
            }
            @Override
            public <T> Optional<T> withResource(Function<Jedis, T> action) {
                return Optional.empty(); // Disables HeartbeatPublisher effectively
            }
        };

        FleetView stubFleetView = new FleetView(fakeConnector) {
            @Override
            public Optional<FleetSnapshot> snapshot() {
                return stubFleet;
            }
        };
        
        presence = new PresenceService(fakeConnector, identity, stubFleetView, executors, logger, Duration.ofMillis(50), STALENESS_WINDOW);
        presence.start();
    }

    @AfterEach
    void tearDown() {
        if (presence != null) {
            presence.close();
        }
    }

    @Test
    void testRetentionWithinStalenessWindow() throws InterruptedException {
        ServerIdentity node = new ServerIdentity(new ServerId("lobby-1"), new ServerRole("lobby"));
        FleetSnapshot snapshot = FleetSnapshot.of(List.of(node));

        // 1. Initial healthy read
        stubFleet = Optional.of(snapshot);
        Thread.sleep(100); // Wait for tick
        assertTrue(presence.cachedFleet().contains(node.id()), "Node should be cached on healthy read");
        assertFalse(presence.isStale(), "Presence should not be stale immediately after read");

        // 2. Fault: read fails (returns empty Optional), but within staleness window
        stubFleet = Optional.empty();
        Thread.sleep(100); // Wait for tick
        assertTrue(presence.cachedFleet().contains(node.id()), "Node should be RETAINED within staleness window");
        assertFalse(presence.isStale(), "Presence should not be stale within the window");

        // 3. Expire: wait for window to pass
        Thread.sleep(STALENESS_WINDOW.toMillis() + 100);
        assertTrue(presence.isStale(), "Presence should be stale after the window elapses");
        // Wait for next tick to clear it
        Thread.sleep(100);
        assertFalse(presence.cachedFleet().contains(node.id()), "Node should be EVICTED after staleness window elapses");
    }

    @Test
    void testAuthoritativeEmptyReadIsAdopted() throws InterruptedException {
        // If the fleet is empty but Redis is up, we should adopt it immediately and not treat it as a failure
        stubFleet = Optional.of(FleetSnapshot.empty());
        // 300ms = 6× the tick interval; gives ample headroom for the scheduler under full-suite load,
        // while staying well inside the 500ms staleness window that would otherwise clear lastGoodRead.
        Thread.sleep(300);
        
        assertEquals(0, presence.cachedFleet().members().size(), "Fleet should be empty");
        assertFalse(presence.isStale(), "An authoritative empty read is a GOOD read, so it should not be stale");
    }
}
