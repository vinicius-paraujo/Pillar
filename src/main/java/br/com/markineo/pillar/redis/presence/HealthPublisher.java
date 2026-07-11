package br.com.markineo.pillar.redis.presence;

import br.com.markineo.pillar.redis.RedisKeys;
import br.com.markineo.pillar.redis.lifecycle.RedisConnector;
import com.google.gson.Gson;
import br.com.markineo.pillar.core.health.HealthProvider;
import br.com.markineo.pillar.core.health.HealthSnapshot;
import br.com.markineo.pillar.core.identity.ServerId;


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
        HealthSnapshot current = provider.current();
        String json = gson.toJson(current);
        long ttlSeconds = HeartbeatPublisher.TTL.toSeconds();

        connector.withResource(jedis -> jedis.setex(RedisKeys.health(self), ttlSeconds, json));
    }
}
