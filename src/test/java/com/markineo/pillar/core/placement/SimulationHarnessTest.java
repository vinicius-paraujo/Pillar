package com.markineo.pillar.core.placement;

import com.markineo.pillar.core.health.HealthSnapshot;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.identity.ServerIdentity;
import com.markineo.pillar.core.identity.ServerRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationHarnessTest {

    private ReservationRegistry reservations;
    private EligibilityFilter filter;
    private PlacementSelector selector;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        reservations = new ReservationRegistry(Clock.systemUTC(), Duration.ofSeconds(10));
        HardCaps caps = new HardCaps(100, 0.9, 50.0);
        filter = new EligibilityFilter(caps);
        selector = new PlacementSelector(reservations);
        executor = Executors.newFixedThreadPool(16); // High concurrency pool
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void testFairDistributionAcrossLargeFleet() throws InterruptedException {
        int fleetSize = 20;
        int totalRequests = 10_000;
        
        List<ServerIdentity> fleet = new ArrayList<>();
        Map<ServerIdentity, HealthSnapshot> healthMap = new ConcurrentHashMap<>();
        
        for (int i = 0; i < fleetSize; i++) {
            ServerIdentity node = node("node-" + i);
            fleet.add(node);
            healthMap.put(node, snapshot(0, 0)); // Empty nodes
        }

        Map<ServerIdentity, AtomicInteger> distribution = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                List<ServerIdentity> eligible = fleet.stream()
                        .filter(node -> {
                            HealthSnapshot health = healthMap.get(node);
                            return health == null || filter.isEligible(health);
                        })
                        .toList();
                Optional<ServerIdentity> result = selector.select(eligible, healthMap::get);
                if (result.isPresent()) {
                    ServerIdentity selected = result.get();
                    reservations.reserve(selected);
                    distribution.computeIfAbsent(selected, k -> new AtomicInteger()).incrementAndGet();
                }
                latch.countDown();
            });
        }

        latch.await();

        assertEquals(fleetSize, distribution.size(), "Every node should have received some traffic");

        int expectedAverage = totalRequests / fleetSize;
        int tolerance = (int) (expectedAverage * 0.15); // 15% tolerance due to P2C randomness

        for (Map.Entry<ServerIdentity, AtomicInteger> entry : distribution.entrySet()) {
            int count = entry.getValue().get();
            assertTrue(Math.abs(count - expectedAverage) <= tolerance, 
                "Node " + entry.getKey().id().value() + " received " + count + " requests, expected ~" + expectedAverage);
        }
    }

    @Test
    void testHardCapsAreStrictlyHonoredUnderLoad() throws InterruptedException {
        int totalRequests = 1_000;
        List<ServerIdentity> fleet = new ArrayList<>();
        Map<ServerIdentity, HealthSnapshot> healthMap = new ConcurrentHashMap<>();

        // 5 Healthy nodes
        for (int i = 0; i < 5; i++) {
            ServerIdentity node = node("healthy-" + i);
            fleet.add(node);
            healthMap.put(node, snapshot(0, 0)); 
        }

        // 5 Unhealthy nodes (MSPT > 50.0)
        for (int i = 0; i < 5; i++) {
            ServerIdentity node = node("unhealthy-" + i);
            fleet.add(node);
            healthMap.put(node, new HealthSnapshot(60.0, 1024, 2048, 0, 0, 0)); 
        }

        Map<ServerIdentity, AtomicInteger> distribution = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                List<ServerIdentity> eligible = fleet.stream()
                        .filter(node -> {
                            HealthSnapshot health = healthMap.get(node);
                            return health == null || filter.isEligible(health);
                        })
                        .toList();
                Optional<ServerIdentity> result = selector.select(eligible, healthMap::get);
                if (result.isPresent()) {
                    ServerIdentity selected = result.get();
                    reservations.reserve(selected);
                    distribution.computeIfAbsent(selected, k -> new AtomicInteger()).incrementAndGet();
                }
                latch.countDown();
            });
        }

        latch.await();

        assertEquals(5, distribution.size(), "Only healthy nodes should receive traffic");
        
        for (ServerIdentity node : distribution.keySet()) {
            assertTrue(node.id().value().startsWith("healthy-"), "Unhealthy node was selected!");
        }
    }

    @Test
    void testDegradedModeUnderLoad() throws InterruptedException {
        int fleetSize = 10;
        int totalRequests = 1_000;
        
        List<ServerIdentity> fleet = new ArrayList<>();
        Map<ServerIdentity, HealthSnapshot> healthMap = new ConcurrentHashMap<>();
        
        for (int i = 0; i < fleetSize; i++) {
            ServerIdentity node = node("dark-" + i);
            fleet.add(node);
            // Omitting healthMap.put(node, null) since ConcurrentHashMap forbids null values.
            // healthMap::get will return null naturally.
        }

        Map<ServerIdentity, AtomicInteger> distribution = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                List<ServerIdentity> eligible = fleet.stream()
                        .filter(node -> {
                            HealthSnapshot health = healthMap.get(node);
                            return health == null || filter.isEligible(health);
                        })
                        .toList();
                Optional<ServerIdentity> result = selector.select(eligible, healthMap::get);
                if (result.isPresent()) {
                    ServerIdentity selected = result.get();
                    reservations.reserve(selected);
                    distribution.computeIfAbsent(selected, k -> new AtomicInteger()).incrementAndGet();
                }
                latch.countDown();
            });
        }

        latch.await();

        assertEquals(fleetSize, distribution.size(), "Every dark node should receive traffic");

        int expectedAverage = totalRequests / fleetSize;
        int tolerance = (int) (expectedAverage * 0.15); 

        for (Map.Entry<ServerIdentity, AtomicInteger> entry : distribution.entrySet()) {
            int count = entry.getValue().get();
            assertTrue(Math.abs(count - expectedAverage) <= tolerance, 
                "Node " + entry.getKey().id().value() + " received " + count + " requests in degraded mode, expected ~" + expectedAverage);
        }
    }

    private ServerIdentity node(String id) {
        return new ServerIdentity(new ServerId(id), new ServerRole("minigame"));
    }

    private HealthSnapshot snapshot(int players, int pendingSignals) {
        return new HealthSnapshot(20.0, 1024, 2048, players, 0, pendingSignals);
    }
}
