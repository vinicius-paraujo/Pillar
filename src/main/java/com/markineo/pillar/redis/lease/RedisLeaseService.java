package com.markineo.pillar.redis.lease;

import com.markineo.pillar.core.lease.Lease;
import com.markineo.pillar.core.lease.LeaseService;
import com.markineo.pillar.core.lease.OwnerToken;
import com.markineo.pillar.core.lease.ResourceKey;
import com.markineo.pillar.redis.RedisKeys;
import com.markineo.pillar.redis.lifecycle.RedisConnector;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.Optional;

public class RedisLeaseService implements LeaseService {

    private static final String SCRIPT_RENEW =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('pexpire', KEYS[1], ARGV[2]) " +
            "else " +
            "    return 0 " +
            "end";

    private static final String SCRIPT_RELEASE =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";

    private final RedisConnector redis;

    public RedisLeaseService(RedisConnector redis) {
        this.redis = redis;
    }

    @Override
    public Optional<Lease> acquire(ResourceKey resource, OwnerToken owner, Duration ttl) {
        String key = RedisKeys.lease(resource);
        return redis.withResource(jedis -> {
            SetParams params = SetParams.setParams().nx().px(ttl.toMillis());
            String result = jedis.set(key, owner.value(), params);
            if ("OK".equals(result)) {
                return new Lease(resource, owner);
            }
            return null;
        });
    }

    @Override
    public boolean renew(Lease lease, Duration newTtl) {
        String key = RedisKeys.lease(lease.resource());
        Optional<Long> result = redis.withResource(jedis -> {
            Object evalResult = jedis.eval(SCRIPT_RENEW, 1, key, lease.owner().value(), String.valueOf(newTtl.toMillis()));
            return evalResult instanceof Long ? (Long) evalResult : 0L;
        });
        return result.orElse(0L) == 1L;
    }

    @Override
    public boolean release(Lease lease) {
        String key = RedisKeys.lease(lease.resource());
        Optional<Long> result = redis.withResource(jedis -> {
            Object evalResult = jedis.eval(SCRIPT_RELEASE, 1, key, lease.owner().value());
            return evalResult instanceof Long ? (Long) evalResult : 0L;
        });
        return result.orElse(0L) == 1L;
    }
}
