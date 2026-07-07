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
import com.markineo.pillar.error.ConfigurationException;
import com.markineo.pillar.logger.PillarLogger;
import com.markineo.pillar.redis.PresenceService;
import com.markineo.pillar.redis.RedisConnector;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

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

        ServerIdentity identity = new ServerIdentity(new ServerId(settings.name()), new ServerRole(settings.role()));
        this.presence = new PresenceService(redis, identity, executors);
        presence.start();

        logger.info("Pillar initialized as '" + settings.name() + "'.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (presence != null) {
            presence.close();
        }
        if (redis != null) {
            redis.close();
        }
        logger.info("Pillar shut down.");
    }
}
