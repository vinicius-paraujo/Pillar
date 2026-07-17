package br.com.markineo.pillar.redis.transport;

import br.com.markineo.pillar.concurrent.PillarExecutors;
import br.com.markineo.pillar.core.fleet.FleetSnapshot;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.identity.ServerRole;
import br.com.markineo.pillar.logger.PillarLogger;
import br.com.markineo.pillar.redis.RedisIntegrationTest;
import br.com.markineo.pillar.redis.presence.FleetView;
import br.com.markineo.pillar.redis.presence.PresenceService;
import br.com.markineo.pillar.redis.RedisKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboxReaperIntegrationTest extends RedisIntegrationTest {

    private PillarExecutors executors;
    private PillarLogger logger;
    private PresenceService stubPresence;
    private InboxReaper reaper;

    private Optional<FleetSnapshot> stubFleet = Optional.of(FleetSnapshot.empty());

    @BeforeEach
    void setUpReaper() {
        executors = new PillarExecutors(new PillarLogger(LoggerFactory.getLogger("test")));
        logger = new PillarLogger(LoggerFactory.getLogger("test"));

        ServerIdentity proxyIdentity = new ServerIdentity(new ServerId("proxy-1"), new ServerRole("proxy"));

        FleetView stubFleetView = new FleetView(connector) {
            @Override
            public Optional<FleetSnapshot> snapshot() {
                return stubFleet;
            }
        };

        stubPresence = new PresenceService(connector, proxyIdentity, stubFleetView, executors, logger, Duration.ofMillis(100), Duration.ofSeconds(30)) {
            @Override
            public FleetSnapshot cachedFleet() {
                return stubFleet.orElse(FleetSnapshot.empty());
            }
        };

        reaper = new InboxReaper(connector, stubPresence, executors, logger, 30000);
        // We won't start() the reaper to avoid unpredictable scheduler timing. We'll invoke reap() manually.
    }

    @AfterEach
    void tearDownReaper() {
        if (stubPresence != null) {
            stubPresence.close();
        }
        if (reaper != null) {
            reaper.close();
        }
    }

    @Test
    void reapDeletesAbandonedInboxAndAttemptsAfterGracePeriod() {
        ServerId deadId = new ServerId("dead-1");
        String inboxKey = RedisKeys.inbox(deadId);
        String attemptsKey = RedisKeys.attempts(deadId);

        // 1. Setup abandoned keys in Redis
        connector.withResource(jedis -> {
            jedis.xadd(inboxKey, (redis.clients.jedis.StreamEntryID) null, java.util.Map.of("field", "value"));
            jedis.hset(attemptsKey, "msg-1", "1");
            return null;
        });

        // Ensure keys exist before reap
        assertTrue(keyExists(inboxKey), "Inbox should exist before reap");
        assertTrue(keyExists(attemptsKey), "Attempts should exist before reap");

        // 2. Invoke reap 1st time (adds to orphanCandidates)
        invokeReap();
        
        assertTrue(keyExists(inboxKey), "Inbox should NOT be deleted on first cycle (grace period)");

        // 3. Invoke reap 2nd time (deletes)
        invokeReap();

        // 4. Verify deletion
        assertFalse(keyExists(inboxKey), "Inbox should be deleted after grace period");
        assertFalse(keyExists(attemptsKey), "Attempts should be deleted after grace period");
    }

    @Test
    void reapAbortsIfNodeInCachedFleet() {
        ServerId aliveId = new ServerId("alive-1");
        String inboxKey = RedisKeys.inbox(aliveId);
        String attemptsKey = RedisKeys.attempts(aliveId);

        connector.withResource(jedis -> {
            jedis.xadd(inboxKey, (redis.clients.jedis.StreamEntryID) null, java.util.Map.of("field", "value"));
            jedis.hset(attemptsKey, "msg-1", "1");
            return null;
        });

        // Node is in the fleet!
        stubFleet = Optional.of(FleetSnapshot.of(java.util.List.of(
                new ServerIdentity(aliveId, new ServerRole("hub"))
        )));

        invokeReap();
        invokeReap(); // Even after two cycles, it shouldn't delete

        // Should NOT delete
        assertTrue(keyExists(inboxKey), "Inbox should NOT be deleted if node is in fleet");
        assertTrue(keyExists(attemptsKey), "Attempts should NOT be deleted if node is in fleet");
    }

    @Test
    void reapAbortsIfPresenceKeyExists() {
        ServerId recoveringId = new ServerId("recovering-1");
        String inboxKey = RedisKeys.inbox(recoveringId);
        String attemptsKey = RedisKeys.attempts(recoveringId);
        String presenceKey = RedisKeys.presence(recoveringId);

        connector.withResource(jedis -> {
            jedis.xadd(inboxKey, (redis.clients.jedis.StreamEntryID) null, java.util.Map.of("field", "value"));
            jedis.hset(attemptsKey, "msg-1", "1");
            jedis.set(presenceKey, "alive"); // Node is writing presence, but maybe not in fleet yet
            return null;
        });

        invokeReap();
        invokeReap(); // Even after two cycles, it shouldn't delete

        // Should NOT delete (atomic lua script should have returned 0)
        assertTrue(keyExists(inboxKey), "Inbox should NOT be deleted if presence key exists");
        assertTrue(keyExists(attemptsKey), "Attempts should NOT be deleted if presence key exists");
    }

    private boolean keyExists(String key) {
        return Boolean.TRUE.equals(connector.withResource(jedis -> jedis.exists(key)).orElse(false));
    }

    private void invokeReap() {
        try {
            java.lang.reflect.Method reap = InboxReaper.class.getDeclaredMethod("reap");
            reap.setAccessible(true);
            reap.invoke(reaper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
