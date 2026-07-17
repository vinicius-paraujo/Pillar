package br.com.markineo.pillar.redis.presence;

import br.com.markineo.pillar.concurrent.PillarExecutors;
import br.com.markineo.pillar.core.health.HealthSnapshot;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.logger.PillarLogger;

import br.com.markineo.pillar.core.placement.ReservationRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class HealthRegistry implements AutoCloseable {

    private final PresenceService presence;
    private final HealthView healthView;
    private final PillarExecutors executors;
    private final PillarLogger logger;
    private final long intervalMillis;
    private final Duration stalenessWindow;
    private final ReservationRegistry reservations;

    private volatile Map<ServerId, HealthSnapshot> cache = Map.of();
    private volatile Instant lastGoodRead = Instant.EPOCH;
    private ScheduledExecutorService loop;

    public HealthRegistry(PresenceService presence, HealthView healthView,
                          PillarExecutors executors, PillarLogger logger,
                          Duration interval, Duration stalenessWindow, ReservationRegistry reservations) {
        this.presence = presence;
        this.healthView = healthView;
        this.executors = executors;
        this.logger = logger;
        this.intervalMillis = interval.toMillis();
        this.stalenessWindow = stalenessWindow;
        this.reservations = reservations;
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

    public int missingHealthCount() {
        return Math.max(0, presence.cachedFleet().size() - cache.size());
    }

    public Optional<Duration> oldestSnapshotAge() {
        if (lastGoodRead == Instant.EPOCH) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(lastGoodRead, Instant.now()));
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

            Optional<Map<ServerId, HealthSnapshot>> result = healthView.fetchAll(activeIds);
            if (result.isPresent()) {
                this.cache = Map.copyOf(result.get());
                lastGoodRead = Instant.now();
            } else {
                if (Instant.now().isAfter(lastGoodRead.plus(stalenessWindow))) {
                    this.cache = Map.of();
                }
            }

            Set<ServerIdentity> aliveNodes = Set.copyOf(presence.cachedFleet().members());
            reservations.cleanUpDeadNodes(aliveNodes);
        } catch (RuntimeException e) {
            logger.error("Health cache tick failed; it will retry on the next interval.", e);
        }
    }
}
