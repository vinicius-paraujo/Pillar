package br.com.markineo.pillar.redis.lease;

import br.com.markineo.pillar.core.lease.Lease;
import br.com.markineo.pillar.core.lease.LeaseService;
import br.com.markineo.pillar.core.lease.OwnerToken;
import br.com.markineo.pillar.core.lease.ResourceKey;
import br.com.markineo.pillar.redis.RedisKeys;
import br.com.markineo.pillar.redis.lifecycle.RedisConnector;
import redis.clients.jedis.params.SetParams;
import java.util.List;

import java.time.Duration;
import java.util.Optional;

public class RedisLeaseService implements LeaseService {

    // Plain EVAL, not EVALSHA: these scripts are ~100 bytes and lease operations are
    // infrequent, so SHA caching would add machinery for no measurable saving.
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
        Optional<Object> result = redis.withResource(jedis ->
                jedis.eval(SCRIPT_RENEW,
                        List.of(key),
                        List.of(lease.owner().value(), String.valueOf(newTtl.toMillis()))));
        return result.map(Long.valueOf(1L)::equals).orElse(false);
    }

    @Override
    public boolean release(Lease lease) {
        String key = RedisKeys.lease(lease.resource());
        Optional<Object> result = redis.withResource(jedis ->
                jedis.eval(SCRIPT_RELEASE,
                        List.of(key),
                        List.of(lease.owner().value())));
        return result.map(Long.valueOf(1L)::equals).orElse(false);
    }
}
