package com.markineo.pillar.redis;

import com.markineo.pillar.concurrent.PillarExecutors;
import com.markineo.pillar.core.fleet.FleetSnapshot;
import com.markineo.pillar.core.identity.ServerIdentity;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class PresenceService implements AutoCloseable {

    private final HeartbeatPublisher publisher;
    private final FleetView fleetView;
    private final PillarExecutors executors;

    private ScheduledExecutorService heartbeat;

    public PresenceService(RedisConnector connector, ServerIdentity identity, PillarExecutors executors) {
        this.publisher = new HeartbeatPublisher(connector, identity);
        this.fleetView = new FleetView(connector);
        this.executors = executors;
    }

    public void start() {
        this.heartbeat = executors.newSingleThreadScheduled("heartbeat");
        long intervalMillis = HeartbeatPublisher.INTERVAL.toMillis();
        heartbeat.scheduleWithFixedDelay(publisher::publish, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public FleetSnapshot fleet() {
        return fleetView.snapshot();
    }

    @Override
    public void close() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
    }
}
