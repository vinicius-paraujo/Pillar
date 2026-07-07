package com.markineo.pillar.config;

public record RedisSettings(String host, int port, String password) {

    public static RedisSettings from(ConfigurationFile config) {
        return new RedisSettings(
                config.requireString("redis.host"),
                config.requireInt("redis.port"),
                config.getString("redis.password", ""));
    }
}
