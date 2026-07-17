package br.com.markineo.pillar.core.placement;

import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.identity.ServerRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReservationRegistryTest {

    private ServerIdentity node(String name) {
        return new ServerIdentity(new ServerId(name), new ServerRole("backend"));
    }

    @Test
    void activeReservationsExpireAfterTtl() {
        MutableClock clock = new MutableClock();
        ReservationRegistry registry = new ReservationRegistry(clock, Duration.ofSeconds(5));
        ServerIdentity node = node("node-1");

        assertEquals(0, registry.activeReservations(node));

        registry.reserve(node);
        registry.reserve(node);
        assertEquals(2, registry.activeReservations(node));

        // Advance 3 seconds (not expired yet)
        clock.advance(Duration.ofSeconds(3));
        registry.reserve(node);
        assertEquals(3, registry.activeReservations(node));

        // Advance 3 more seconds (first 2 expire, the 3rd remains)
        clock.advance(Duration.ofSeconds(3));
        assertEquals(1, registry.activeReservations(node));

        // Advance 5 more seconds (all expire)
        clock.advance(Duration.ofSeconds(5));
        assertEquals(0, registry.activeReservations(node));
    }

    @Test
    void cleanUpDeadNodesRemovesEntriesAfterInactivity() {
        MutableClock clock = new MutableClock();
        ReservationRegistry registry = new ReservationRegistry(clock, Duration.ofMinutes(5));

        ServerIdentity node1 = node("node-1");
        ServerIdentity node2 = node("node-2");

        registry.reserve(node1);
        registry.reserve(node2);

        // Nodes are in aliveNodes, so they aren't removed immediately.
        registry.cleanUpDeadNodes(java.util.Set.of(node1, node2));
        assertEquals(1, registry.activeReservations(node1));
        assertEquals(1, registry.activeReservations(node2));

        // Advance past the 2-minute inactivity threshold
        clock.advance(Duration.ofMinutes(2).plusSeconds(1));

        // Refresh node2's access time
        registry.activeReservations(node2);

        // Nodes are still alive, but node1 has been inactive for > 2m
        registry.cleanUpDeadNodes(java.util.Set.of(node1, node2));

        // node1 should have 0 active reservations because it was cleaned up due to inactivity
        assertEquals(0, registry.activeReservations(node1));
        
        // node2 should still have its reservation because it was recently accessed
        assertEquals(1, registry.activeReservations(node2));
    }

    private static class MutableClock extends Clock {
        private Instant current = Instant.now();

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
