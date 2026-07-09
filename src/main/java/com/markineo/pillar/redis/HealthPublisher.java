package com.markineo.pillar.redis;

import com.google.gson.Gson;
import com.markineo.pillar.core.health.HealthProvider;
import com.markineo.pillar.core.health.HealthSnapshot;
import com.markineo.pillar.core.identity.ServerId;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisException;

public final class HealthPublisher {

    private final RedisConnector connector;
    private final ServerId self;
    private final HealthProvider provider;
    private final Gson gson;

    public HealthPublisher(RedisConnector connector, ServerId self, HealthProvider provider, Gson gson) {
        this.connector = connector;
        this.self = self;
        this.provider = provider;
        this.gson = gson;
    }

    public void publish() {
        if (!connector.isReady()) {
            return;
        }
        HealthSnapshot current = provider.current();
        String json = gson.toJson(current);
        long ttlSeconds = HeartbeatPublisher.TTL.toSeconds();

        try (Jedis jedis = connector.pool().getResource()) {
            jedis.setex(RedisKeys.health(self), ttlSeconds, json);
        } catch (JedisException e) {
            // Like HeartbeatPublisher, transient redis faults are owned by the connector.
        }
    }
}
