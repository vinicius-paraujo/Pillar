package com.markineo.pillar.redis;

import com.google.gson.Gson;
import com.markineo.pillar.concurrent.PillarExecutors;
import com.markineo.pillar.core.health.HealthProvider;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.logger.PillarLogger;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class HealthService implements AutoCloseable {

    private static final long INTERVAL_MILLIS = 5000;

    private final HealthPublisher publisher;
    private final PillarExecutors executors;
    private final PillarLogger logger;

    private ScheduledExecutorService loop;

    public HealthService(RedisConnector connector, ServerId self, HealthProvider provider,
                         Gson gson, PillarExecutors executors, PillarLogger logger) {
        this.publisher = new HealthPublisher(connector, self, provider, gson);
        this.executors = executors;
        this.logger = logger;
    }

    public void start() {
        this.loop = executors.newSingleThreadScheduled("health");
        loop.scheduleWithFixedDelay(this::tick, 0, INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (loop != null) {
            loop.shutdownNow();
        }
    }

    private void tick() {
        try {
            publisher.publish();
        } catch (RuntimeException e) {
            logger.error("Health tick failed; publishing continues on the next interval.", e);
        }
    }
}
