package br.com.markineo.pillar.config;

import java.time.Duration;

import br.com.markineo.pillar.core.placement.HardCaps;

public record PillarSettings(String name, String role, RedisSettings redis, String language,
                             Duration healthInterval, Duration consumerDedupWindow,
                             int consumerPoolSize, int consumerQueueCapacity,
                             String entryRole, HardCaps hardCaps) {

    public static PillarSettings from(ConfigurationFile config) {
        return new PillarSettings(
                config.requireString("server.name"),
                config.requireString("server.role"),
                RedisSettings.from(config),
                config.getString("language", "en-us"),
                Duration.ofMillis(config.getInt("health.interval", 5000)),
                Duration.ofSeconds(config.getInt("consumer.dedup-window", 600)),
                config.getInt("consumer.pool-size", 4),
                config.getInt("consumer.queue-capacity", 128),
                null,
                null);
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
                config.getInt("consumer.queue-capacity", 128),
                config.getString("routing.entry-role", "hub"),
                new HardCaps(
                        config.getInt("routing.hard-caps.max-players", 100),
                        config.getDouble("routing.hard-caps.max-memory-percent", 0.9),
                        config.getDouble("routing.hard-caps.max-mspt", 45.0)
                ));
    }
}
