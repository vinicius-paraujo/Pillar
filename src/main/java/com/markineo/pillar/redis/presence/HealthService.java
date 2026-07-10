package com.markineo.pillar.redis.presence;

import com.markineo.pillar.redis.lifecycle.RedisConnector;
import com.google.gson.Gson;
import com.markineo.pillar.concurrent.PillarExecutors;
import com.markineo.pillar.core.health.HealthProvider;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.logger.PillarLogger;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class HealthService implements AutoCloseable {

    private final HealthPublisher publisher;
    private final PillarExecutors executors;
    private final PillarLogger logger;
    private final long intervalMillis;

    private ScheduledExecutorService loop;

    public HealthService(RedisConnector connector, ServerId self, HealthProvider provider,
                         Gson gson, PillarExecutors executors, PillarLogger logger, java.time.Duration interval) {
        this.publisher = new HealthPublisher(connector, self, provider, gson);
        this.executors = executors;
        this.logger = logger;
        this.intervalMillis = interval.toMillis();
    }

    public void start() {
        this.loop = executors.newSingleThreadScheduled("health");
        loop.scheduleWithFixedDelay(this::tick, 0, intervalMillis, TimeUnit.MILLISECONDS);
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
