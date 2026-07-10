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
import com.google.gson.Gson;
import com.markineo.pillar.redis.lifecycle.RedisConnector;
import com.markineo.pillar.redis.presence.HealthRegistry;
import com.markineo.pillar.redis.presence.HealthService;
import com.markineo.pillar.redis.presence.HealthView;
import com.markineo.pillar.redis.presence.PresenceService;
import com.markineo.pillar.redis.transport.InboxDiagnostics;
import com.markineo.pillar.redis.transport.JsonEnvelopeCodec;
import com.markineo.pillar.redis.transport.PingHandler;
import com.markineo.pillar.redis.transport.RequestSender;
import com.markineo.pillar.redis.transport.StreamConsumer;
import com.markineo.pillar.redis.transport.StreamPublisher;
import com.markineo.pillar.velocity.command.PillarCommand;
import com.markineo.pillar.velocity.handler.RoutePlayerHandler;
import com.markineo.pillar.velocity.listener.LoginListener;
import com.markineo.pillar.velocity.tasks.VelocityScheduler;
import com.markineo.pillar.core.placement.EligibilityFilter;
import com.markineo.pillar.core.placement.PlacementSelector;
import com.markineo.pillar.core.placement.PlacementService;
import com.markineo.pillar.core.placement.ReservationRegistry;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
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
    private HealthRegistry healthRegistry;
    private HealthService health;
    private ScheduledExecutorService timeoutScheduler;
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

        logger.info("Enabling Pillar v0.1.0, using language '" + settings.language() + "'.");

        this.executors = new PillarExecutors(logger);
        this.redis = new RedisConnector(settings.redis(), executors, logger);
        redis.start();

        ServerId selfId = new ServerId(settings.name());
        ServerIdentity identity = new ServerIdentity(selfId, new ServerRole(settings.role()));
        this.presence = new PresenceService(redis, identity, executors, logger);
        presence.start();

        Gson gson = new Gson();
        HealthView healthView = new HealthView(redis, gson);
        this.healthRegistry = new HealthRegistry(presence, healthView, executors, logger, settings.healthInterval());
        healthRegistry.start();

        // Placement (Login Routing)
        Duration reservationTtl = settings.healthInterval().multipliedBy(3).dividedBy(2); // 1.5x
        ReservationRegistry reservations = new ReservationRegistry(Clock.systemUTC(), reservationTtl);
        EligibilityFilter eligibilityFilter = new EligibilityFilter(settings.hardCaps());
        PlacementSelector placementSelector = new PlacementSelector(reservations);
        PlacementService placement = new PlacementService(eligibilityFilter, placementSelector, reservations);

        JsonEnvelopeCodec codec = new JsonEnvelopeCodec();
        this.timeoutScheduler = executors.newSingleThreadScheduled("correlation-timeout");
        this.correlations = new CorrelationRegistry(timeoutScheduler);

        StreamPublisher publisher = new StreamPublisher(redis, codec);
        HandlerRegistry handlers = new HandlerRegistry(correlations);
        handlers.register(new PingHandler(selfId, publisher, logger));
        handlers.register(new RoutePlayerHandler(server, placement, presence, healthRegistry, publisher, selfId, gson, logger));

        this.consumer = new StreamConsumer(redis, codec, selfId, executors, logger, handlers.asSink(codec, logger), 
                                           settings.consumerDedupWindow(), settings.consumerPoolSize(), settings.consumerQueueCapacity());
        consumer.start();

        VelocityHealthProvider healthProvider = new VelocityHealthProvider(server, consumer);
        this.health = new HealthService(redis, selfId, healthProvider, gson, executors, logger, settings.healthInterval());
        health.start();

        RequestSender requestSender = new RequestSender(publisher, correlations);

        CommandManager commands = server.getCommandManager();
        CommandMeta meta = commands.metaBuilder("pillar").plugin(this).build();
        commands.register(meta, new PillarCommand(
                lang, configurations, presence, redis, new InboxDiagnostics(redis), requestSender,
                new VelocityScheduler(), selfId, logger));

        // Placement (Login Routing)
        server.getEventManager().register(this, new LoginListener(
                server, placement, presence, healthRegistry, settings, lang, logger
        ));

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
        if (timeoutScheduler != null) {
            timeoutScheduler.shutdownNow();
        }
        if (health != null) {
            health.close();
        }
        if (healthRegistry != null) {
            healthRegistry.close();
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
