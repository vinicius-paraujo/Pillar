package com.markineo.pillar.redis.presence;

import com.markineo.pillar.redis.RedisKeys;
import com.markineo.pillar.redis.lifecycle.RedisConnector;
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
    private static final String INITIAL_SCAN_CURSOR = ScanParams.SCAN_POINTER_START;
    private static final int SCAN_BATCH_SIZE = 100;

    private final RedisConnector connector;

    public FleetView(RedisConnector connector) {
        this.connector = connector;
    }

    public FleetSnapshot snapshot() {
        return connector.withResource(jedis -> {
            List<String> keys = scanPresenceKeys(jedis);
            if (keys.isEmpty()) {
                return FleetSnapshot.empty();
            }
            return buildSnapshot(keys, jedis.mget(keys.toArray(new String[0])));
        }).orElse(FleetSnapshot.empty());
    }

    private List<String> scanPresenceKeys(Jedis jedis) {
        ScanParams scanParams = new ScanParams()
                .match(RedisKeys.presencePattern())
                .count(SCAN_BATCH_SIZE);

        List<String> presenceKeys = new ArrayList<>();
        String cursor = INITIAL_SCAN_CURSOR;

        while (true) {
            ScanResult<String> scan = jedis.scan(cursor, scanParams);

            presenceKeys.addAll(scan.getResult());

            cursor = scan.getCursor();
            if (isScanComplete(cursor)) {
                return presenceKeys;
            }
        }
    }

    private boolean isScanComplete(String cursor) {
        return INITIAL_SCAN_CURSOR.equals(cursor);
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
