package com.markineo.pillar.config;

public record PillarSettings(String name, String role, RedisSettings redis, String language) {

    public static PillarSettings from(ConfigurationFile config) {
        return new PillarSettings(
                config.requireString("server.name"),
                config.requireString("server.role"),
                RedisSettings.from(config),
                config.getString("language", "en-us"));
    }

    public static PillarSettings fromProxy(ConfigurationFile config) {
        return new PillarSettings(
                config.requireString("proxy.name"),
                "proxy",
                RedisSettings.from(config),
                config.getString("language", "en-us"));
    }
}
