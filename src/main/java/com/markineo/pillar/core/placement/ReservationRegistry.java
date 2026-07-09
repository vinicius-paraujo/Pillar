package com.markineo.pillar.core.placement;

import com.markineo.pillar.core.identity.ServerIdentity;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class ReservationRegistry {

    private final Clock clock;
    private final Duration ttl;
    private final ConcurrentHashMap<ServerIdentity, ConcurrentLinkedQueue<Long>> reservations;

    public ReservationRegistry(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
        this.reservations = new ConcurrentHashMap<>();
    }

    public void reserve(ServerIdentity node) {
        reservations.computeIfAbsent(node, k -> new ConcurrentLinkedQueue<>())
                .offer(clock.millis() + ttl.toMillis());
    }

    public int activeReservations(ServerIdentity node) {
        ConcurrentLinkedQueue<Long> queue = reservations.get(node);
        if (queue == null || queue.isEmpty()) {
            return 0;
        }

        long now = clock.millis();
        
        // Evict expired
        while (!queue.isEmpty() && queue.peek() != null && queue.peek() <= now) {
            queue.poll();
        }

        return queue.size();
    }
}
