package com.markineo.pillar.redis;

import com.markineo.pillar.core.fleet.FleetSnapshot;
import com.markineo.pillar.core.identity.ServerIdentity;
import com.markineo.pillar.core.identity.ServerRole;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.List;

public final class FleetView {

    private final RedisConnector connector;

    public FleetView(RedisConnector connector) {
        this.connector = connector;
    }

    public FleetSnapshot snapshot() {
        if (!connector.isReady()) {
            return FleetSnapshot.empty();
        }
        try (Jedis jedis = connector.pool().getResource()) {
            List<String> keys = scanPresenceKeys(jedis);
            if (keys.isEmpty()) {
                return FleetSnapshot.empty();
            }
            return buildSnapshot(keys, jedis.mget(keys.toArray(new String[0])));
        } catch (JedisException e) {
            // A degraded read yields an empty view rather than a failure; the connector's
            // health loop owns the state transition and presence resumes on reconnect.
            return FleetSnapshot.empty();
        }
    }

    private List<String> scanPresenceKeys(Jedis jedis) {
        ScanParams params = new ScanParams().match(RedisKeys.presencePattern()).count(100);
        List<String> keys = new ArrayList<>();
        String cursor = ScanParams.SCAN_POINTER_START;
        do {
            ScanResult<String> result = jedis.scan(cursor, params);
            keys.addAll(result.getResult());
            cursor = result.getCursor();
        } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
        return keys;
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
