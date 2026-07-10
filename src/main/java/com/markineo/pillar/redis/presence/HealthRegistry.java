package com.markineo.pillar.redis.presence;

import com.markineo.pillar.concurrent.PillarExecutors;
import com.markineo.pillar.core.health.HealthSnapshot;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.identity.ServerIdentity;
import com.markineo.pillar.logger.PillarLogger;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class HealthRegistry implements AutoCloseable {

    private final PresenceService presence;
    private final HealthView healthView;
    private final PillarExecutors executors;
    private final PillarLogger logger;
    private final long intervalMillis;

    private volatile Map<ServerId, HealthSnapshot> cache = Map.of();
    private ScheduledExecutorService loop;

    public HealthRegistry(PresenceService presence, HealthView healthView,
                          PillarExecutors executors, PillarLogger logger,
                          Duration interval) {
        this.presence = presence;
        this.healthView = healthView;
        this.executors = executors;
        this.logger = logger;
        this.intervalMillis = interval.toMillis();
    }

    public void start() {
        this.loop = executors.newSingleThreadScheduled("health-cache");
        loop.scheduleWithFixedDelay(this::tick, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (loop != null) {
            loop.shutdownNow();
        }
    }

    public Map<ServerId, HealthSnapshot> snapshot() {
        return cache;
    }

    private void tick() {
        try {
            Collection<ServerId> activeIds = presence.cachedFleet().members().stream()
                    .map(ServerIdentity::id)
                    .collect(Collectors.toSet());

            if (activeIds.isEmpty()) {
                this.cache = Map.of();
                return;
            }

            // HealthView.fetchAll catches JedisException and returns an empty map on failure.
            // When that happens, this map will become empty, triggering the least-connections
            // fallback behavior for the placement logic, which is the correct degraded state.
            this.cache = Map.copyOf(healthView.fetchAll(activeIds));
        } catch (RuntimeException e) {
            logger.error("Health cache tick failed; it will retry on the next interval.", e);
        }
    }
}
