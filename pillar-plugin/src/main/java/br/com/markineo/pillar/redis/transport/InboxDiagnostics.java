package br.com.markineo.pillar.redis.transport;

import br.com.markineo.pillar.redis.RedisKeys;
import br.com.markineo.pillar.redis.lifecycle.RedisConnector;
import br.com.markineo.pillar.core.identity.ServerId;

import redis.clients.jedis.resps.StreamPendingSummary;

public final class InboxDiagnostics {

    private final RedisConnector connector;

    public InboxDiagnostics(RedisConnector connector) {
        this.connector = connector;
    }

    public long pendingEntries(ServerId self) {
        return connector.withResource(jedis -> {
            StreamPendingSummary summary =
                    jedis.xpending(RedisKeys.inbox(self), StreamProtocol.CONSUMER_GROUP);
            return summary == null ? 0L : summary.getTotal();
        }).orElse(0L);
    }
}
