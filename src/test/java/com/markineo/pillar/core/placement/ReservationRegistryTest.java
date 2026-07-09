package com.markineo.pillar.core.placement;

import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.identity.ServerIdentity;
import com.markineo.pillar.core.identity.ServerRole;
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
