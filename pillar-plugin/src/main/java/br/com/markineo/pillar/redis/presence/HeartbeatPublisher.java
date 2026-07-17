package br.com.markineo.pillar.redis.presence;

import br.com.markineo.pillar.redis.RedisKeys;
import br.com.markineo.pillar.redis.lifecycle.RedisConnector;
import br.com.markineo.pillar.core.identity.ServerIdentity;


import java.time.Duration;

public final class HeartbeatPublisher {

    public static final Duration INTERVAL = Duration.ofSeconds(3);

    // TTL outlives several intervals so a single missed beat does not evict a live node;
    // once beats stop entirely, the key expires and the node drops from every fleet view.
    public static final Duration TTL = INTERVAL.multipliedBy(3);

    private final RedisConnector connector;
    private final ServerIdentity identity;

    public HeartbeatPublisher(RedisConnector connector, ServerIdentity identity) {
        this.connector = connector;
        this.identity = identity;
    }

    public void publish() {
        connector.withResource(jedis -> jedis.setex(RedisKeys.presence(identity.id()), TTL.toSeconds(), identity.role().value()));
    }
}
