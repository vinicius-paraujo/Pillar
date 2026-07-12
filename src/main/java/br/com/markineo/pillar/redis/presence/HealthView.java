package br.com.markineo.pillar.redis.presence;

import br.com.markineo.pillar.redis.RedisKeys;
import br.com.markineo.pillar.redis.lifecycle.RedisConnector;
import com.google.gson.Gson;
import br.com.markineo.pillar.core.health.HealthSnapshot;
import br.com.markineo.pillar.core.identity.ServerId;


import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class HealthView {

    private final RedisConnector connector;
    private final Gson gson;

    public HealthView(RedisConnector connector, Gson gson) {
        this.connector = connector;
        this.gson = gson;
    }

    public Optional<HealthSnapshot> fetch(ServerId id) {
        return connector.withResource(jedis -> {
            String json = jedis.get(RedisKeys.health(id));
            if (json == null) {
                return null;
            }
            try {
                return gson.fromJson(json, HealthSnapshot.class);
            } catch (RuntimeException ignored) {
                return null;
            }
        });
    }

    public Optional<Map<ServerId, HealthSnapshot>> fetchAll(Collection<ServerId> ids) {
        if (ids.isEmpty()) {
            return Optional.of(Map.of());
        }
        return connector.withResource(jedis -> {
            ServerId[] idArray = ids.toArray(new ServerId[0]);
            String[] keys = new String[idArray.length];
            for (int i = 0; i < idArray.length; i++) {
                keys[i] = RedisKeys.health(idArray[i]);
            }

            List<String> values = jedis.mget(keys);
            Map<ServerId, HealthSnapshot> result = new HashMap<>();
            for (int i = 0; i < idArray.length; i++) {
                String json = values.get(i);
                if (json != null) {
                    try {
                        result.put(idArray[i], gson.fromJson(json, HealthSnapshot.class));
                    } catch (RuntimeException ignored) {
                        // Poison data (malformed JSON or invalid values), skip this node
                    }
                }
            }
            return result;
        });
    }
}
