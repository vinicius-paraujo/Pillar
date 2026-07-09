package com.markineo.pillar.redis;

import com.google.gson.Gson;
import com.markineo.pillar.core.health.HealthSnapshot;
import com.markineo.pillar.core.identity.ServerId;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisException;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class HealthView {

    private final RedisConnector connector;
    private final Gson gson;

    public HealthView(RedisConnector connector, Gson gson) {
        this.connector = connector;
        this.gson = gson;
    }

    public Optional<HealthSnapshot> fetch(ServerId id) {
        if (!connector.isReady()) {
            return Optional.empty();
        }
        try (Jedis jedis = connector.pool().getResource()) {
            String json = jedis.get(RedisKeys.health(id));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(gson.fromJson(json, HealthSnapshot.class));
        } catch (JedisException e) {
            return Optional.empty();
        }
    }

    public Map<ServerId, HealthSnapshot> fetchAll(Collection<ServerId> ids) {
        if (!connector.isReady() || ids.isEmpty()) {
            return Map.of();
        }
        ServerId[] idArray = ids.toArray(new ServerId[0]);
        String[] keys = new String[idArray.length];
        for (int i = 0; i < idArray.length; i++) {
            keys[i] = RedisKeys.health(idArray[i]);
        }

        try (Jedis jedis = connector.pool().getResource()) {
            List<String> values = jedis.mget(keys);
            Map<ServerId, HealthSnapshot> result = new HashMap<>();
            for (int i = 0; i < idArray.length; i++) {
                String json = values.get(i);
                if (json != null) {
                    result.put(idArray[i], gson.fromJson(json, HealthSnapshot.class));
                }
            }
            return result;
        } catch (JedisException e) {
            return Map.of();
        }
    }
}
