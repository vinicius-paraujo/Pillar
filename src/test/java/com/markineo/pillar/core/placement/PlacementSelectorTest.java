package com.markineo.pillar.core.placement;

import com.markineo.pillar.core.health.HealthSnapshot;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.identity.ServerIdentity;
import com.markineo.pillar.core.identity.ServerRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementSelectorTest {

    private final ReservationRegistry reservations = new ReservationRegistry(Clock.systemUTC(), Duration.ofSeconds(5));
    private final PlacementSelector selector = new PlacementSelector(reservations);

    private ServerIdentity node(String name) {
        return new ServerIdentity(new ServerId(name), new ServerRole("backend"));
    }

    private HealthSnapshot snapshot(int players, int pendingSignals) {
        return new HealthSnapshot(20.0, 1024L, 4096L, players, 2, pendingSignals);
    }

    @Test
    void returnsEmptyWhenNoNodes() {
        Optional<ServerIdentity> result = selector.select(List.of(), id -> snapshot(0, 0));
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsTheOnlyNodeWhenSizeIsOne() {
        ServerIdentity node1 = node("node-1");
        Optional<ServerIdentity> result = selector.select(List.of(node1), id -> snapshot(100, 100));
        assertTrue(result.isPresent());
        assertEquals(node1, result.get());
    }

    @Test
    void prefersNodeWithLowerScoreWhenTwoNodes() {
        ServerIdentity node1 = node("node-1");
        ServerIdentity node2 = node("node-2");

        // node1 has score 15, node2 has score 5
        Map<ServerIdentity, HealthSnapshot> healthMap = Map.of(
                node1, snapshot(10, 5),
                node2, snapshot(2, 3)
        );

        // Run multiple times to ensure P2C deterministically picks the better node when N=2
        for (int i = 0; i < 10; i++) {
            Optional<ServerIdentity> result = selector.select(List.of(node1, node2), healthMap::get);
            assertTrue(result.isPresent());
            assertEquals(node2, result.get(), "Should select node2 because 2+3 < 10+5");
        }
    }

    @Test
    void tiesAreResolvedArbitrarilyButDeterministicallyWithinP2C() {
        ServerIdentity node1 = node("node-1");
        ServerIdentity node2 = node("node-2");

        // Both have score 10
        Map<ServerIdentity, HealthSnapshot> healthMap = Map.of(
                node1, snapshot(5, 5),
                node2, snapshot(8, 2)
        );

        Optional<ServerIdentity> result = selector.select(List.of(node1, node2), healthMap::get);
        assertTrue(result.isPresent());
        // Just verify it returns one of them without throwing
        assertTrue(result.get().equals(node1) || result.get().equals(node2));
    }

    @Test
    void includesActiveReservationsInScoreCalculation() {
        ServerIdentity node1 = node("node-1");
        ServerIdentity node2 = node("node-2");

        // node1 inherent score: 5+0 = 5
        // node2 inherent score: 10+0 = 10
        Map<ServerIdentity, HealthSnapshot> healthMap = Map.of(
                node1, snapshot(5, 0),
                node2, snapshot(10, 0)
        );

        // Add 10 reservations to node1, making its total score 15.
        for (int i = 0; i < 10; i++) {
            reservations.reserve(node1);
        }

        // Now node2 (score 10) should be preferred over node1 (score 15)
        for (int i = 0; i < 10; i++) {
            Optional<ServerIdentity> result = selector.select(List.of(node1, node2), healthMap::get);
            assertTrue(result.isPresent());
            assertEquals(node2, result.get(), "Should select node2 because 10+0 < 5+0+10(reservations)");
        }
    }

    @Test
    void prefersNodeWithHealthOverNodeWithoutHealth() {
        ServerIdentity node1 = node("node-1");
        ServerIdentity node2 = node("node-2");

        // node1 has no health (dark node), node2 has health but is fully loaded
        Map<ServerIdentity, HealthSnapshot> healthMap = new java.util.HashMap<>();
        healthMap.put(node1, null);
        healthMap.put(node2, snapshot(1000, 100));

        // P2C should absolutely prefer node2 over node1
        Optional<ServerIdentity> result = selector.select(List.of(node1, node2), healthMap::get);
        assertTrue(result.isPresent());
        assertEquals(node2, result.get(), "Should select node2 because a healthy node beats a dark node");
    }

    @Test
    void fallsBackToLeastConnectionsWhenBothLackHealth() {
        ServerIdentity node1 = node("node-1");
        ServerIdentity node2 = node("node-2");

        // Both are dark nodes
        Map<ServerIdentity, HealthSnapshot> healthMap = new java.util.HashMap<>();
        healthMap.put(node1, null);
        healthMap.put(node2, null);

        // node1 gets 5 reservations, node2 gets 2 reservations
        for (int i = 0; i < 5; i++) reservations.reserve(node1);
        for (int i = 0; i < 2; i++) reservations.reserve(node2);

        // P2C should fallback to activeReservations and pick node2
        for (int i = 0; i < 10; i++) {
            Optional<ServerIdentity> result = selector.select(List.of(node1, node2), healthMap::get);
            assertTrue(result.isPresent());
            assertEquals(node2, result.get(), "Should select node2 on least-connections fallback");
        }
    }
}
