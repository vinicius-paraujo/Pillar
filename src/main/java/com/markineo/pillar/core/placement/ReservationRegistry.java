package com.markineo.pillar.core.placement;

import com.markineo.pillar.core.identity.ServerIdentity;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

public final class ReservationRegistry {

    private final Clock clock;
    private final Duration ttl;
    private final ConcurrentHashMap<ServerIdentity, NodeReservations> reservations;

    public ReservationRegistry(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
        this.reservations = new ConcurrentHashMap<>();
    }

    public void reserve(ServerIdentity node) {
        long now = clock.millis();
        NodeReservations nodeReservations = reservations.computeIfAbsent(node, k -> new NodeReservations(now));
        nodeReservations.markAccessed(now);
        nodeReservations.add(now + ttl.toMillis());
    }

    public int activeReservations(ServerIdentity node) {
        NodeReservations nodeReservations = reservations.get(node);
        if (nodeReservations == null || nodeReservations.isEmpty()) {
            return 0;
        }

        long now = clock.millis();
        nodeReservations.markAccessed(now);
        nodeReservations.evictExpired(now);

        return nodeReservations.size();
    }

    public void cleanUpDeadNodes(java.util.Set<ServerIdentity> aliveNodes) {
        long expireThreshold = clock.millis() - Duration.ofMinutes(2).toMillis();
        reservations.entrySet().removeIf(entry -> 
                !aliveNodes.contains(entry.getKey()) || entry.getValue().getLastAccessTime() < expireThreshold
        );
    }

    private static final class NodeReservations {
        private final java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        private volatile long lastAccessTime;

        NodeReservations(long now) {
            this.lastAccessTime = now;
        }

        void markAccessed(long now) {
            this.lastAccessTime = now;
        }

        long getLastAccessTime() {
            return lastAccessTime;
        }

        synchronized void add(long expirationTimestamp) {
            queue.offer(expirationTimestamp);
        }

        synchronized void evictExpired(long now) {
            while (!queue.isEmpty() && queue.peek() != null && queue.peek() <= now) {
                queue.poll();
            }
        }

        synchronized boolean isEmpty() {
            return queue.isEmpty();
        }

        synchronized int size() {
            return queue.size();
        }
    }
}
