package com.markineo.pillar.config;

import java.time.Duration;

public record PillarSettings(String name, String role, RedisSettings redis, String language,
                             Duration healthInterval, Duration consumerDedupWindow,
                             int consumerPoolSize, int consumerQueueCapacity) {

    public static PillarSettings from(ConfigurationFile config) {
        return new PillarSettings(
                config.requireString("server.name"),
                config.requireString("server.role"),
                RedisSettings.from(config),
                config.getString("language", "en-us"),
                Duration.ofMillis(config.getInt("health.interval", 5000)),
                Duration.ofSeconds(config.getInt("consumer.dedup-window", 600)),
                config.getInt("consumer.pool-size", 4),
                config.getInt("consumer.queue-capacity", 128));
    }

    public static PillarSettings fromProxy(ConfigurationFile config) {
        return new PillarSettings(
                config.requireString("proxy.name"),
                "proxy",
                RedisSettings.from(config),
                config.getString("language", "en-us"),
                Duration.ofMillis(config.getInt("health.interval", 5000)),
                Duration.ofSeconds(config.getInt("consumer.dedup-window", 600)),
                config.getInt("consumer.pool-size", 4),
                config.getInt("consumer.queue-capacity", 128));
    }
}
