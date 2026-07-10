package com.markineo.pillar.redis.presence;

import com.markineo.pillar.concurrent.PillarExecutors;
import com.markineo.pillar.core.fleet.FleetSnapshot;
import com.markineo.pillar.core.health.HealthSnapshot;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.identity.ServerIdentity;
import com.markineo.pillar.core.identity.ServerRole;
import com.markineo.pillar.logger.PillarLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.exceptions.JedisException;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HealthRegistryTest {

    private PillarExecutors executors;
    private PillarLogger logger;
    
    private AtomicReference<FleetSnapshot> stubbedFleet;
    private AtomicReference<Map<ServerId, HealthSnapshot>> stubbedHealth;
    private AtomicReference<RuntimeException> fetchException;

    private PresenceService stubPresence;
    private HealthView stubHealthView;

    private HealthRegistry registry;

    @BeforeEach
    void setUp() {
        executors = new PillarExecutors(new PillarLogger(LoggerFactory.getLogger("test")));
        logger = new PillarLogger(LoggerFactory.getLogger("test"));

        stubbedFleet = new AtomicReference<>(FleetSnapshot.empty());
        stubbedHealth = new AtomicReference<>(Map.of());
        fetchException = new AtomicReference<>(null);

        stubPresence = new PresenceService(null, null, executors, logger) {
            @Override
            public FleetSnapshot cachedFleet() {
                return stubbedFleet.get();
            }
            @Override
            public void start() {}
            @Override
            public void close() {}
        };

        stubHealthView = new HealthView(null, null) {
            @Override
            public Map<ServerId, HealthSnapshot> fetchAll(Collection<ServerId> ids) {
                if (fetchException.get() != null) {
                    throw fetchException.get();
                }
                // Simulate HealthView behavior: missing ids are just absent from map
                Map<ServerId, HealthSnapshot> all = stubbedHealth.get();
                return ids.stream()
                        .filter(all::containsKey)
                        .collect(java.util.stream.Collectors.toMap(id -> id, all::get));
            }
        };

        // Use a short interval for test to tick quickly
        registry = new HealthRegistry(stubPresence, stubHealthView, executors, logger, Duration.ofMillis(10));
        registry.start();
    }

    @AfterEach
    void tearDown() {
        if (registry != null) registry.close();
    }

    @Test
    void updatesCacheWhenFleetHasData() throws InterruptedException {
        ServerId id = new ServerId("alpha");
        stubbedFleet.set(FleetSnapshot.of(List.of(new ServerIdentity(id, new ServerRole("hub")))));
        stubbedHealth.set(Map.of(id, new HealthSnapshot(20.0, 100, 200, 5, 1, 0)));

        Thread.sleep(50); // wait for tick

        Map<ServerId, HealthSnapshot> snapshot = registry.snapshot();
        assertTrue(snapshot.containsKey(id));
        assertEquals(5, snapshot.get(id).players());
    }

    @Test
    void missingNodeDataResultsInAbsenceFromCache() throws InterruptedException {
        ServerId id1 = new ServerId("alpha");
        ServerId id2 = new ServerId("beta"); // No health data for beta
        
        stubbedFleet.set(FleetSnapshot.of(List.of(
                new ServerIdentity(id1, new ServerRole("hub")),
                new ServerIdentity(id2, new ServerRole("hub"))
        )));
        
        stubbedHealth.set(Map.of(id1, new HealthSnapshot(20.0, 100, 200, 5, 1, 0)));

        Thread.sleep(50);

        Map<ServerId, HealthSnapshot> snapshot = registry.snapshot();
        assertTrue(snapshot.containsKey(id1));
        assertFalse(snapshot.containsKey(id2), "Node without health data should be absent");
    }

    @Test
    void emptyFleetClearsCache() throws InterruptedException {
        ServerId id = new ServerId("alpha");
        stubbedFleet.set(FleetSnapshot.of(List.of(new ServerIdentity(id, new ServerRole("hub")))));
        stubbedHealth.set(Map.of(id, new HealthSnapshot(20.0, 100, 200, 5, 1, 0)));

        Thread.sleep(50);
        assertFalse(registry.snapshot().isEmpty());

        // Now fleet goes empty
        stubbedFleet.set(FleetSnapshot.empty());
        Thread.sleep(50);

        assertTrue(registry.snapshot().isEmpty(), "Cache should be empty when fleet is empty");
    }

    @Test
    void fetchFailureMaintainsDegradedState() throws InterruptedException {
        ServerId id = new ServerId("alpha");
        stubbedFleet.set(FleetSnapshot.of(List.of(new ServerIdentity(id, new ServerRole("hub")))));
        
        // Initial success
        stubbedHealth.set(Map.of(id, new HealthSnapshot(20.0, 100, 200, 5, 1, 0)));
        Thread.sleep(50);
        assertFalse(registry.snapshot().isEmpty());

        // Now fetching throws RuntimeException (like GSON bug)
        fetchException.set(new RuntimeException("Simulated unexpected failure"));
        Thread.sleep(50);

        // Map is NOT cleared if exception is caught in HealthRegistry tick
        assertFalse(registry.snapshot().isEmpty(), "Unexpected exceptions should retain previous good state");

        fetchException.set(null);
        
        // However, if HealthView returns empty (simulating JedisException caught inside HealthView)
        stubbedHealth.set(Map.of());
        Thread.sleep(50);
        assertTrue(registry.snapshot().isEmpty(), "Cache should clear when HealthView returns empty due to Redis failure");
    }
}
