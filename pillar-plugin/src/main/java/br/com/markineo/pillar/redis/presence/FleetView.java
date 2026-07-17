package br.com.markineo.pillar.redis.presence;

import br.com.markineo.pillar.redis.RedisKeys;
import br.com.markineo.pillar.redis.lifecycle.RedisConnector;
import br.com.markineo.pillar.core.fleet.FleetSnapshot;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.identity.ServerRole;
import redis.clients.jedis.Jedis;



import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FleetView {
    private static final int SCAN_BATCH_SIZE = 100;

    private final RedisConnector connector;

    public FleetView(RedisConnector connector) {
        this.connector = connector;
    }

    public Optional<FleetSnapshot> snapshot() {
        return connector.withResource(jedis -> {
            List<String> keys = scanPresenceKeys(jedis);
            if (keys.isEmpty()) {
                return FleetSnapshot.empty();
            }
            return buildSnapshot(keys, jedis.mget(keys.toArray(new String[0])));
        });
    }

    private List<String> scanPresenceKeys(Jedis jedis) {
        return RedisConnector.scanKeys(jedis, RedisKeys.presencePattern(), SCAN_BATCH_SIZE);
    }

    private FleetSnapshot buildSnapshot(List<String> keys, List<String> roles) {
        List<ServerIdentity> members = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            String role = roles.get(i);
            // Null role means the key expired between scan and read: the node is gone, skip it.
            if (role != null) {
                members.add(new ServerIdentity(RedisKeys.presenceId(keys.get(i)), new ServerRole(role)));
            }
        }
        return FleetSnapshot.of(members);
    }
}
