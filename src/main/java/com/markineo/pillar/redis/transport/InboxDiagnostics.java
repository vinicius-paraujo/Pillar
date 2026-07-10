package com.markineo.pillar.redis.transport;

import com.markineo.pillar.redis.RedisKeys;
import com.markineo.pillar.redis.lifecycle.RedisConnector;
import com.markineo.pillar.core.identity.ServerId;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.resps.StreamPendingSummary;

public final class InboxDiagnostics {

    private final RedisConnector connector;

    public InboxDiagnostics(RedisConnector connector) {
        this.connector = connector;
    }

    // Entries delivered to this node's consumer but not yet acked — a handler or ack failure
    // leaves them here, invisible until PIL-40 recovery. Zero when Redis is unreachable, which
    // the caller distinguishes from a healthy zero by also reading the connection state.
    public long pendingEntries(ServerId self) {
        return connector.withResource(jedis -> {
            StreamPendingSummary summary =
                    jedis.xpending(RedisKeys.inbox(self), StreamProtocol.CONSUMER_GROUP);
            return summary == null ? 0L : summary.getTotal();
        }).orElse(0L);
    }
}
