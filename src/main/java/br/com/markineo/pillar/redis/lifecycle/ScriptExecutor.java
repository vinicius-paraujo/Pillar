package br.com.markineo.pillar.redis.lifecycle;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisDataException;

import java.util.List;
import java.util.Optional;

public final class ScriptExecutor {

    private final String script;
    private volatile String sha;

    public ScriptExecutor(String script) {
        this.script = script;
    }

    public <T> Optional<T> eval(RedisConnector connector, List<String> keys, List<String> args) {
        return connector.withResource(jedis -> eval(jedis, keys, args));
    }

    public <T> T eval(Jedis jedis, List<String> keys, List<String> args) {
        String currentSha = sha;
        if (currentSha == null) {
            currentSha = jedis.scriptLoad(script);
            sha = currentSha;
        }

        try {
            @SuppressWarnings("unchecked")
            T result = (T) jedis.evalsha(currentSha, keys, args);
            return result;
        } catch (JedisDataException e) {
            if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
                currentSha = jedis.scriptLoad(script);
                sha = currentSha;
                @SuppressWarnings("unchecked")
                T result = (T) jedis.evalsha(currentSha, keys, args);
                return result;
            }
            throw e;
        }
    }
}
