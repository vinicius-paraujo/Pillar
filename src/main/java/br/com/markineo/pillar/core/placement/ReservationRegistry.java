package br.com.markineo.pillar.core.placement;

import br.com.markineo.pillar.core.identity.ServerIdentity;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// A single lock over a plain map: reservation traffic (logins + programmatic routes)
// is far below the contention level that would justify per-node locking.
public final class ReservationRegistry {

    private static final Duration IDLE_EVICTION = Duration.ofMinutes(2);

    private final Clock clock;
    private final Duration ttl;
    private final Map<ServerIdentity, NodeReservations> reservations = new HashMap<>();

    public ReservationRegistry(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    public synchronized void reserve(ServerIdentity node) {
        long now = clock.millis();
        NodeReservations nodeReservations =
                reservations.computeIfAbsent(node, k -> new NodeReservations());
        nodeReservations.lastAccessTime = now;
        nodeReservations.expiries.addLast(now + ttl.toMillis());
    }

    public synchronized int activeReservations(ServerIdentity node) {
        NodeReservations nodeReservations = reservations.get(node);
        if (nodeReservations == null) {
            return 0;
        }

        long now = clock.millis();
        nodeReservations.lastAccessTime = now;
        nodeReservations.evictExpired(now);

        return nodeReservations.expiries.size();
    }

    public synchronized void cleanUpDeadNodes(Set<ServerIdentity> aliveNodes) {
        long idleThreshold = clock.millis() - IDLE_EVICTION.toMillis();
        reservations.entrySet().removeIf(entry ->
                !aliveNodes.contains(entry.getKey()) || entry.getValue().lastAccessTime < idleThreshold
        );
    }

    private static final class NodeReservations {
        private final ArrayDeque<Long> expiries = new ArrayDeque<>();
        private long lastAccessTime;

        private void evictExpired(long now) {
            while (!expiries.isEmpty() && expiries.peekFirst() <= now) {
                expiries.pollFirst();
            }
        }
    }
}
