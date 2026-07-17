package br.com.markineo.pillar.velocity;

import com.google.inject.Inject;
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
import com.google.gson.Gson;
import br.com.markineo.pillar.redis.lifecycle.RedisConnector;
import br.com.markineo.pillar.redis.presence.HealthRegistry;
import br.com.markineo.pillar.redis.presence.HealthService;
import br.com.markineo.pillar.redis.presence.HealthView;
import br.com.markineo.pillar.redis.presence.PresenceService;
import br.com.markineo.pillar.redis.presence.FleetView;
import br.com.markineo.pillar.redis.transport.InboxDiagnostics;
import br.com.markineo.pillar.redis.transport.JsonEnvelopeCodec;
import br.com.markineo.pillar.redis.transport.PingHandler;
import br.com.markineo.pillar.redis.transport.ReloadConfigHandler;
import br.com.markineo.pillar.redis.transport.RequestSender;
import br.com.markineo.pillar.redis.transport.StreamConsumer;
import br.com.markineo.pillar.redis.transport.StreamPublisher;
import br.com.markineo.pillar.velocity.command.PillarCommand;
import br.com.markineo.pillar.velocity.handler.BroadcastHandler;
import br.com.markineo.pillar.velocity.handler.RoutePlayerHandler;
import br.com.markineo.pillar.velocity.handler.SendPlayerMessageHandler;
import br.com.markineo.pillar.velocity.listener.LoginListener;
import br.com.markineo.pillar.velocity.tasks.VelocityScheduler;
import br.com.markineo.pillar.core.placement.EligibilityFilter;
import br.com.markineo.pillar.core.placement.PlacementSelector;
import br.com.markineo.pillar.core.placement.PlacementService;
import br.com.markineo.pillar.core.placement.ReservationRegistry;
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
    private br.com.markineo.pillar.redis.transport.InboxReaper inboxReaper;
    private java.util.concurrent.ThreadPoolExecutor ioPool;
    private final com.velocitypowered.api.plugin.PluginContainer container;

    @Inject
    public PillarVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory, com.velocitypowered.api.plugin.PluginContainer container) {
        this.server = server;
        this.logger = new PillarLogger(logger);
        this.dataDirectory = dataDirectory;
        this.container = container;
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

        String version = container.getDescription().getVersion().orElse("unknown");
        logger.info("Enabling Pillar v" + version + ", using language '" + settings.language() + "'.");

        this.executors = new PillarExecutors(logger);
        this.redis = new RedisConnector(settings.redis(), executors, logger);
        redis.start();

        ServerId selfId = new ServerId(settings.name());
        ServerIdentity identity = new ServerIdentity(selfId, new ServerRole(settings.role()));

        // Placement (Login Routing)
        FleetView fleetView = new FleetView(redis);
        HealthView healthView = new HealthView(redis, new Gson());
        ReservationRegistry reservations = new ReservationRegistry(Clock.systemUTC(), Duration.ofSeconds(5));

        Duration interval = br.com.markineo.pillar.redis.presence.HeartbeatPublisher.INTERVAL;
        Duration stalenessWindow = settings.redis().stalenessWindow();
        
        this.presence = new PresenceService(redis, identity, fleetView, executors, logger, interval, stalenessWindow);
        presence.start();

        this.healthRegistry = new HealthRegistry(presence, healthView, executors, logger, Duration.ofMillis(100), stalenessWindow, reservations);
        healthRegistry.start();
        EligibilityFilter eligibilityFilter = new EligibilityFilter(settings.hardCaps());
        PlacementSelector placementSelector = new PlacementSelector(reservations);
        br.com.markineo.pillar.core.placement.DecisionBuffer decisionBuffer = new br.com.markineo.pillar.core.placement.DecisionBuffer(15);
        PlacementService placement = new PlacementService(eligibilityFilter, placementSelector, reservations, decisionBuffer);

        Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
        JsonEnvelopeCodec codec = new JsonEnvelopeCodec(gson);
        this.timeoutScheduler = executors.newSingleThreadScheduled("correlation-timeout");
        this.correlations = new CorrelationRegistry(timeoutScheduler);

        StreamPublisher publisher = new StreamPublisher(redis, codec);
        HandlerRegistry handlers = new HandlerRegistry(correlations);
        handlers.register(new PingHandler(selfId, publisher, logger));
        handlers.register(new RoutePlayerHandler(server, placement, presence, healthRegistry, publisher, selfId, gson, logger));
        handlers.register(new SendPlayerMessageHandler(server, gson, logger));
        handlers.register(new BroadcastHandler(server, gson, logger));
        handlers.register(new ReloadConfigHandler(configurations, selfId, publisher));

        this.consumer = new StreamConsumer(
                redis,
                codec,
                selfId,
                executors,
                logger,
                handlers.asSink(codec, logger),
                settings.consumerDedupWindow(),
                settings.consumerPoolSize(),
                settings.consumerQueueCapacity()
        );
        consumer.start();

        VelocityHealthProvider healthProvider = new VelocityHealthProvider(server, consumer);
        this.health = new HealthService(redis, selfId, healthProvider, gson, executors, logger, settings.healthInterval());
        health.start();

        this.inboxReaper = new br.com.markineo.pillar.redis.transport.InboxReaper(redis, presence, executors, logger, settings.redis().reaperInterval());
        inboxReaper.start();

        RequestSender requestSender = new RequestSender(publisher, correlations);

        CommandManager commands = server.getCommandManager();
        CommandMeta meta = commands.metaBuilder("pillar").plugin(this).build();
        commands.register(meta, new PillarCommand(
                lang, configurations, presence, redis, new InboxDiagnostics(redis), requestSender,
                new VelocityScheduler(), selfId, logger, consumer, inboxReaper, healthRegistry, decisionBuffer));

        // Login event listener
        server.getEventManager().register(this, new LoginListener(
                server, placement, presence, healthRegistry, settings, lang, logger, redis
        ));

        logger.info("Pillar initialized as '" + settings.name() + "'.");

        this.ioPool = executors.newBoundedWorkerPool("pillar-io", 4, 1000);
        VelocityScheduler velocityScheduler = new VelocityScheduler();
        MessagingImpl messagingImpl = new MessagingImpl(
                publisher, requestSender, handlers, velocityScheduler, codec, ioPool, selfId, presence, logger
        );

        br.com.markineo.pillar.redis.lease.RedisLeaseService redisLeaseService = new br.com.markineo.pillar.redis.lease.RedisLeaseService(redis);
        br.com.markineo.pillar.redis.lease.LeasesImpl leasesImpl = new br.com.markineo.pillar.redis.lease.LeasesImpl(
                redisLeaseService, ioPool, velocityScheduler, selfId
        );

        PillarFacade facade = new PillarFacade(messagingImpl, leasesImpl);
        PillarProvider.register(facade);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
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
        if (inboxReaper != null) {
            inboxReaper.close();
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
