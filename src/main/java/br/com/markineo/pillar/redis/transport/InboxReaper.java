package br.com.markineo.pillar.redis.transport;

import br.com.markineo.pillar.concurrent.PillarExecutors;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.logger.PillarLogger;
import br.com.markineo.pillar.redis.lifecycle.RedisConnector;
import br.com.markineo.pillar.redis.presence.PresenceService;
import br.com.markineo.pillar.redis.RedisKeys;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class InboxReaper implements AutoCloseable {

    private static final String LUA_SCRIPT = 
            "if redis.call('EXISTS', KEYS[1]) == 0 then\n" +
            "    redis.call('DEL', KEYS[2], KEYS[3])\n" +
            "    return 1\n" +
            "end\n" +
            "return 0";

    private final RedisConnector connector;
    private final PresenceService presence;
    private final PillarExecutors executors;
    private final PillarLogger logger;
    private final long intervalMillis;
    
    private final java.util.Set<ServerId> orphanCandidates = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private ScheduledExecutorService scheduler;

    public InboxReaper(RedisConnector connector, PresenceService presence, 
                       PillarExecutors executors, PillarLogger logger, long intervalMillis) {
        this.connector = connector;
        this.presence = presence;
        this.executors = executors;
        this.logger = logger;
        this.intervalMillis = intervalMillis;
    }

    public void start() {
        this.scheduler = executors.newSingleThreadScheduled("inbox-reaper");
        scheduler.scheduleWithFixedDelay(this::reap, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void reap() {
        connector.withResource(jedis -> {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().match(RedisKeys.inboxPattern()).count(100);

            java.util.Set<ServerId> foundThisCycle = new java.util.HashSet<>();

            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                List<String> keys = result.getResult();
                for (String key : keys) {
                    ServerId id = RedisKeys.parseInboxId(key);
                    if (id != null) {
                        foundThisCycle.add(id);
                        processInbox(jedis, key, id);
                    }
                }
                cursor = result.getCursor();
            } while (!cursor.equals(ScanParams.SCAN_POINTER_START));

            // Clean up candidates that no longer have inboxes (perhaps they were deleted or node recovered and deleted its own)
            orphanCandidates.removeIf(id -> !foundThisCycle.contains(id));

            return null;
        });
    }

    private void processInbox(Jedis jedis, String inboxKey, ServerId id) {
        // 1. Check memory-level gate (cachedFleet)
        if (presence.cachedFleet().contains(id)) {
            orphanCandidates.remove(id); // Healed
            return; // Node is alive in the cached fleet
        }

        // 2. Idle Gate: wait for one full cycle before deleting
        if (!orphanCandidates.contains(id)) {
            // First time seen absent. Add to candidates and wait for next cycle.
            orphanCandidates.add(id);
            return;
        }

        // 3. Atomic check-and-delete via Lua
        String presenceKey = RedisKeys.presence(id);
        String attemptsKey = RedisKeys.attempts(id);

        try {
            Object result = jedis.eval(LUA_SCRIPT, 3, presenceKey, inboxKey, attemptsKey);
            if (result instanceof Long && ((Long) result) == 1L) {
                logger.info("Reaped abandoned inbox and attempts for dead node: " + id.value());
                orphanCandidates.remove(id);
            }
        } catch (redis.clients.jedis.exceptions.JedisException e) {
            logger.error("Failed to execute Lua script for reaping inbox " + inboxKey, e);
        }
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
