package br.com.markineo.pillar.redis.presence;

import br.com.markineo.pillar.redis.lifecycle.RedisConnector;
import br.com.markineo.pillar.concurrent.PillarExecutors;
import br.com.markineo.pillar.core.fleet.FleetSnapshot;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.logger.PillarLogger;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PresenceService implements AutoCloseable {

    private final RedisConnector connector;
    private final HeartbeatPublisher publisher;
    private final FleetView fleetView;
    private final PillarExecutors executors;
    private final PillarLogger logger;
    private final long intervalMillis;
    private final java.time.Duration stalenessWindow;

    // The last fleet read, refreshed on the heartbeat loop so callers on latency-sensitive
    // threads (tab-completion runs on the server main thread) never trigger a live SCAN+MGET.
    private volatile FleetSnapshot cachedFleet = FleetSnapshot.empty();
    private volatile Instant lastGoodRead = Instant.EPOCH;

    private ScheduledExecutorService heartbeat;

    public PresenceService(RedisConnector connector, ServerIdentity identity,
                           FleetView fleetView, PillarExecutors executors, PillarLogger logger,
                           java.time.Duration interval, java.time.Duration stalenessWindow) {
        this.connector = connector;
        this.publisher = new HeartbeatPublisher(connector, identity);
        this.fleetView = fleetView;
        this.executors = executors;
        this.logger = logger;
        this.intervalMillis = interval.toMillis();
        this.stalenessWindow = stalenessWindow;
    }


    public void start() {
        this.heartbeat = executors.newSingleThreadScheduled("heartbeat");
        heartbeat.scheduleWithFixedDelay(this::tick, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    // A live read; the explicit /pillar fleet and ping commands want the freshest view and
    // run once per invocation, not per keystroke.
    public FleetSnapshot fleet() {
        return fleetView.snapshot().orElse(FleetSnapshot.empty());
    }

    // The last cached read; for hot paths that must not block on Redis.
    public FleetSnapshot cachedFleet() {
        return cachedFleet;
    }
    
    // Indicates if the current cached fleet is stale (the retention window has elapsed).
    public boolean isStale() {
        return Instant.now().isAfter(lastGoodRead.plus(stalenessWindow));
    }

    // Immediately removes a dead node from the cached fleet to prevent further routing to it.
    public void evict(ServerId deadNode) {
        FleetSnapshot current = this.cachedFleet;
        if (current.contains(deadNode)) {
            this.cachedFleet = FleetSnapshot.of(
                    current.members().stream()
                            .filter(member -> !member.id().equals(deadNode))
                            .toList()
            );
            logger.warn("Evicted dead node " + deadNode.value() + " from cached fleet proactively.");
        }
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
            Optional<FleetSnapshot> result = fleetView.snapshot();
            if (result.isPresent()) {
                cachedFleet = result.get();
                lastGoodRead = Instant.now();
            } else {
                if (isStale()) {
                    cachedFleet = FleetSnapshot.empty();
                }
            }
        } catch (RuntimeException e) {
            logger.error("Presence tick failed; heartbeat continues on the next interval.", e);
        }
    }
}
