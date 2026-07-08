package com.markineo.pillar.paper;

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
import com.markineo.pillar.paper.commands.PillarCommand;
import com.markineo.pillar.paper.tasks.PaperScheduler;
import com.markineo.pillar.redis.InboxDiagnostics;
import com.markineo.pillar.redis.JsonEnvelopeCodec;
import com.markineo.pillar.redis.PingHandler;
import com.markineo.pillar.redis.PresenceService;
import com.markineo.pillar.redis.RedisConnector;
import com.markineo.pillar.redis.RequestSender;
import com.markineo.pillar.redis.StreamConsumer;
import com.markineo.pillar.redis.StreamPublisher;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ScheduledExecutorService;

public final class Pillar extends JavaPlugin {

    private PillarLogger logger;
    private Configurations configurations;
    private PillarSettings settings;
    private Lang lang;
    private PillarExecutors executors;
    private RedisConnector redis;
    private PresenceService presence;
    private ScheduledExecutorService timeoutScheduler;
    private CorrelationRegistry correlations;
    private StreamConsumer consumer;

    @Override
    public void onEnable() {
        this.logger = new PillarLogger(getSLF4JLogger());

        try {
            FileLoader loader = new FileLoader(getDataFolder().toPath());
            this.configurations = new Configurations(loader);
            this.settings = PillarSettings.from(configurations.get("config.yml"));
            this.lang = new Lang(configurations, settings.language());
        } catch (ConfigurationException e) {
            logger.error("Configuration invalid: " + e.getMessage() + " Disabling Pillar.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        logger.info("Using language '" + settings.language() + "'.");

        this.executors = new PillarExecutors(logger);
        this.redis = new RedisConnector(settings.redis(), executors, logger);
        redis.start();

        ServerId selfId = new ServerId(settings.name());
        ServerIdentity identity = new ServerIdentity(selfId, new ServerRole(settings.role()));
        this.presence = new PresenceService(redis, identity, executors, logger);
        presence.start();

        JsonEnvelopeCodec codec = new JsonEnvelopeCodec();
        this.timeoutScheduler = executors.newSingleThreadScheduled("correlation-timeout");
        this.correlations = new CorrelationRegistry(timeoutScheduler);

        StreamPublisher publisher = new StreamPublisher(redis, codec);
        HandlerRegistry handlers = new HandlerRegistry(correlations);
        handlers.register(new PingHandler(selfId, publisher, logger));

        this.consumer = new StreamConsumer(redis, codec, selfId, executors, logger, handlers.asSink(codec, logger));
        consumer.start();

        RequestSender requestSender = new RequestSender(publisher, correlations);

        getCommand("pillar").setExecutor(new PillarCommand(
                lang, configurations, presence, redis, new InboxDiagnostics(redis), requestSender,
                new PaperScheduler(this), selfId, logger));

        logger.info("Pillar enabled as '" + settings.name() + "' (role " + settings.role() + ").");
    }

    @Override
    public void onDisable() {
        if (consumer != null) {
            consumer.close();
        }
        if (correlations != null) {
            correlations.close();
        }
        if (timeoutScheduler != null) {
            timeoutScheduler.shutdownNow();
        }
        if (presence != null) {
            presence.close();
        }
        if (redis != null) {
            redis.close();
        }
        if (logger != null) {
            logger.info("Pillar disabled.");
        }
    }
}
