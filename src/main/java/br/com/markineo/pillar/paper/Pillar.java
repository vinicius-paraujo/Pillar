package br.com.markineo.pillar.paper;

import br.com.markineo.pillar.concurrent.PillarExecutors;
import br.com.markineo.pillar.config.Configurations;
import br.com.markineo.pillar.config.FileLoader;
import br.com.markineo.pillar.config.Lang;
import br.com.markineo.pillar.config.PillarSettings;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.identity.ServerRole;
import br.com.markineo.pillar.core.task.CorrelationRegistry;
import br.com.markineo.pillar.core.task.HandlerRegistry;
import br.com.markineo.pillar.api.PillarProvider;
import br.com.markineo.pillar.redis.messaging.MessagingImpl;
import br.com.markineo.pillar.error.ConfigurationException;
import br.com.markineo.pillar.logger.PillarLogger;
import br.com.markineo.pillar.paper.commands.PillarCommand;
import br.com.markineo.pillar.paper.tasks.PaperScheduler;
import com.google.gson.Gson;
import br.com.markineo.pillar.redis.presence.HealthService;
import br.com.markineo.pillar.redis.transport.InboxDiagnostics;
import br.com.markineo.pillar.redis.transport.JsonEnvelopeCodec;
import br.com.markineo.pillar.redis.transport.PingHandler;
import br.com.markineo.pillar.redis.transport.ReloadConfigHandler;
import br.com.markineo.pillar.redis.presence.FleetView;
import br.com.markineo.pillar.redis.presence.PresenceService;
import br.com.markineo.pillar.redis.lifecycle.RedisConnector;
import br.com.markineo.pillar.redis.transport.RequestSender;
import br.com.markineo.pillar.redis.transport.StreamConsumer;
import br.com.markineo.pillar.redis.transport.StreamPublisher;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

public final class Pillar extends JavaPlugin {

    private PillarLogger logger;
    private Configurations configurations;
    private PillarSettings settings;
    private Lang lang;
    private PillarExecutors executors;
    private RedisConnector redis;
    private PresenceService presence;
    private HealthService health;
    private ScheduledExecutorService timeoutScheduler;
    private CorrelationRegistry correlations;
    private StreamConsumer consumer;
    private java.util.concurrent.ThreadPoolExecutor ioPool;

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

        String version = getPluginMeta().getVersion();
        logger.info("Enabling Pillar v" + version + ", using language '" + settings.language() + "'.");

        this.executors = new PillarExecutors(logger);
        this.redis = new RedisConnector(settings.redis(), executors, logger);
        redis.start();

        ServerId selfId = new ServerId(settings.name());
        ServerIdentity identity = new ServerIdentity(selfId, new ServerRole(settings.role()));

        FleetView fleetView = new FleetView(redis);
        Duration interval = br.com.markineo.pillar.redis.presence.HeartbeatPublisher.INTERVAL;
        Duration stalenessWindow = settings.redis().stalenessWindow();
        
        this.presence = new PresenceService(redis, identity, fleetView, executors, logger, interval, stalenessWindow);
        presence.start();

        Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
        JsonEnvelopeCodec codec = new JsonEnvelopeCodec(gson);
        this.timeoutScheduler = executors.newSingleThreadScheduled("correlation-timeout");
        this.correlations = new CorrelationRegistry(timeoutScheduler);

        StreamPublisher publisher = new StreamPublisher(redis, codec);
        HandlerRegistry handlers = new HandlerRegistry(correlations);
        handlers.register(new PingHandler(selfId, publisher, logger));
        handlers.register(new ReloadConfigHandler(configurations, selfId, publisher));

        this.consumer = new StreamConsumer(redis, codec, selfId, executors, logger, handlers.asSink(codec, logger), 
                                           settings.consumerDedupWindow(), settings.consumerPoolSize(), settings.consumerQueueCapacity());
        consumer.start();

        PaperHealthProvider healthProvider = new PaperHealthProvider(this, consumer);
        this.health = new HealthService(redis, selfId, healthProvider, gson, executors, logger, settings.healthInterval());
        health.start();

        RequestSender requestSender = new RequestSender(publisher, correlations);

        getCommand("pillar").setExecutor(new PillarCommand(
                lang, configurations, presence, redis, new InboxDiagnostics(redis), requestSender,
                new PaperScheduler(this), selfId, logger, consumer));

        logger.info("Pillar enabled as '" + settings.name() + "' (role " + settings.role() + ").");

        this.ioPool = executors.newBoundedWorkerPool("pillar-io", 4, 1000);
        MessagingImpl messagingImpl = new MessagingImpl(
                publisher, requestSender, handlers, new PaperScheduler(this), codec, ioPool, selfId, fleetView, logger
        );

        br.com.markineo.pillar.redis.lease.RedisLeaseService redisLeaseService = new br.com.markineo.pillar.redis.lease.RedisLeaseService(redis);
        br.com.markineo.pillar.redis.lease.LeasesImpl leasesImpl = new br.com.markineo.pillar.redis.lease.LeasesImpl(
                redisLeaseService, ioPool, new PaperScheduler(this), selfId
        );

        PillarFacade facade = new PillarFacade(messagingImpl, leasesImpl);
        PillarProvider.register(facade);
    }

    @Override
    public void onDisable() {
        PillarProvider.unregister();
        if (consumer != null) {
            consumer.close();
        }
        if (ioPool != null) {
            ioPool.shutdownNow();
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
