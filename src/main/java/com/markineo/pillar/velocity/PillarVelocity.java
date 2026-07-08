package com.markineo.pillar.velocity;

import com.google.inject.Inject;
import com.markineo.pillar.concurrent.PillarExecutors;
import com.markineo.pillar.config.Configurations;
import com.markineo.pillar.config.FileLoader;
import com.markineo.pillar.config.Lang;
import com.markineo.pillar.config.PillarSettings;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.identity.ServerIdentity;
import com.markineo.pillar.core.identity.ServerRole;
import com.markineo.pillar.core.task.CorrelationRegistry;
import com.markineo.pillar.core.task.HandlerRegistry;
import com.markineo.pillar.error.ConfigurationException;
import com.markineo.pillar.logger.PillarLogger;
import com.markineo.pillar.redis.JsonEnvelopeCodec;
import com.markineo.pillar.redis.PingHandler;
import com.markineo.pillar.redis.PresenceService;
import com.markineo.pillar.redis.RedisConnector;
import com.markineo.pillar.redis.StreamConsumer;
import com.markineo.pillar.redis.StreamPublisher;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;

public final class PillarVelocity {

    private final ProxyServer server;
    private final PillarLogger logger;
    private final Path dataDirectory;

    private Configurations configurations;
    private PillarSettings settings;
    private Lang lang;
    private PillarExecutors executors;
    private RedisConnector redis;
    private PresenceService presence;
    private CorrelationRegistry correlations;
    private StreamConsumer consumer;

    @Inject
    public PillarVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = new PillarLogger(logger);
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            FileLoader loader = new FileLoader(dataDirectory);
            this.configurations = new Configurations(loader);
            this.settings = PillarSettings.fromProxy(configurations.get("config-velocity.yml"));
            this.lang = new Lang(configurations, settings.language());
        } catch (ConfigurationException e) {
            // Velocity offers no self-disable; log actionably and stay inert (nothing wired).
            logger.error("Configuration invalid: " + e.getMessage() + " Pillar will not start.", e);
            return;
        }

        logger.info("Using language '" + settings.language() + "'.");

        this.executors = new PillarExecutors(logger);
        this.redis = new RedisConnector(settings.redis(), executors, logger);
        redis.start();

        ServerId selfId = new ServerId(settings.name());
        ServerIdentity identity = new ServerIdentity(selfId, new ServerRole(settings.role()));
        this.presence = new PresenceService(redis, identity, executors);
        presence.start();

        JsonEnvelopeCodec codec = new JsonEnvelopeCodec();
        ScheduledExecutorService timeoutScheduler = executors.newSingleThreadScheduled("correlation-timeout");
        this.correlations = new CorrelationRegistry(timeoutScheduler);

        StreamPublisher publisher = new StreamPublisher(redis, codec);
        HandlerRegistry handlers = new HandlerRegistry(correlations);
        handlers.register(new PingHandler(selfId, publisher, logger));

        this.consumer = new StreamConsumer(redis, codec, selfId, executors, logger, handlers.asSink(codec, logger));
        consumer.start();

        logger.info("Pillar initialized as '" + settings.name() + "'.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (consumer != null) {
            consumer.close();
        }
        if (correlations != null) {
            correlations.close();
        }
        if (presence != null) {
            presence.close();
        }
        if (redis != null) {
            redis.close();
        }
        logger.info("Pillar shut down.");
    }
}
