package com.markineo.pillar.paper;

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
import org.bukkit.plugin.java.JavaPlugin;

public final class Pillar extends JavaPlugin {

    private PillarLogger logger;
    private Configurations configurations;
    private PillarSettings settings;
    private Lang lang;
    private PillarExecutors executors;
    private RedisConnector redis;
    private PresenceService presence;

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

        ServerIdentity identity = new ServerIdentity(new ServerId(settings.name()), new ServerRole(settings.role()));
        this.presence = new PresenceService(redis, identity, executors);
        presence.start();

        logger.info("Pillar enabled as '" + settings.name() + "' (role " + settings.role() + ").");
    }

    @Override
    public void onDisable() {
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
