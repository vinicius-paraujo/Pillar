package br.com.markineo.pillar.config;

import java.time.Duration;

public record RedisSettings(String host, int port, String password,
                            int poolMaxTotal, Duration poolMaxWait,
                            Duration stalenessWindow, long reaperInterval) {

    public static RedisSettings from(ConfigurationFile config) {
        return new RedisSettings(
                config.requireString("redis.host"),
                config.requireInt("redis.port"),
                config.getString("redis.password", ""),
                config.getInt("redis.pool.max-total", 16),
                Duration.ofMillis(config.getInt("redis.pool.max-wait-millis", 2000)),
                Duration.ofSeconds(config.getInt("redis.staleness-window-seconds", 30)),
                config.getInt("redis.reaper-interval-millis", 30000));
    }
}
