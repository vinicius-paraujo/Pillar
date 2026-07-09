package com.markineo.pillar.redis.presence;

import com.markineo.pillar.redis.lifecycle.RedisConnector;
import com.markineo.pillar.concurrent.PillarExecutors;
import com.markineo.pillar.core.fleet.FleetSnapshot;
import com.markineo.pillar.core.identity.ServerIdentity;
import com.markineo.pillar.logger.PillarLogger;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class PresenceService implements AutoCloseable {

    private final HeartbeatPublisher publisher;
    private final FleetView fleetView;
    private final PillarExecutors executors;
    private final PillarLogger logger;

    // The last fleet read, refreshed on the heartbeat loop so callers on latency-sensitive
    // threads (tab-completion runs on the server main thread) never trigger a live SCAN+MGET.
    private volatile FleetSnapshot cachedFleet = FleetSnapshot.empty();

    private ScheduledExecutorService heartbeat;
    private final java.util.List<java.util.function.Consumer<FleetSnapshot>> updateListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public PresenceService(RedisConnector connector, ServerIdentity identity,
                           PillarExecutors executors, PillarLogger logger) {
        this.publisher = new HeartbeatPublisher(connector, identity);
        this.fleetView = new FleetView(connector);
        this.executors = executors;
        this.logger = logger;
    }

    public void onUpdate(java.util.function.Consumer<FleetSnapshot> listener) {
        this.updateListeners.add(listener);
    }

    public void start() {
        this.heartbeat = executors.newSingleThreadScheduled("heartbeat");
        long intervalMillis = HeartbeatPublisher.INTERVAL.toMillis();
        heartbeat.scheduleWithFixedDelay(this::tick, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    // A live read; the explicit /pillar fleet and ping commands want the freshest view and
    // run once per invocation, not per keystroke.
    public FleetSnapshot fleet() {
        return fleetView.snapshot();
    }

    // The last cached read; for hot paths that must not block on Redis.
    public FleetSnapshot cachedFleet() {
        return cachedFleet;
    }

    @Override
    public void close() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
    }

    // Guarded so an unexpected fault never lets scheduleWithFixedDelay silently cancel the
    // loop, which would freeze this node's heartbeat and drop it from every fleet view while
    // it is still alive.
    private void tick() {
        try {
            publisher.publish();
            FleetSnapshot newFleet = fleetView.snapshot();
            cachedFleet = newFleet;
            updateListeners.forEach(listener -> {
                try {
                    listener.accept(newFleet);
                } catch (RuntimeException e) {
                    logger.error("Fleet update listener failed", e);
                }
            });
        } catch (RuntimeException e) {
            logger.error("Presence tick failed; heartbeat continues on the next interval.", e);
        }
    }
}
