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

    private ScheduledExecutorService scheduler;

    public InboxReaper(RedisConnector connector, PresenceService presence, 
                       PillarExecutors executors, PillarLogger logger) {
        this.connector = connector;
        this.presence = presence;
        this.executors = executors;
        this.logger = logger;
    }

    public void start() {
        this.scheduler = executors.newSingleThreadScheduled("inbox-reaper");
        scheduler.scheduleWithFixedDelay(this::reap, 30, 30, TimeUnit.SECONDS);
    }

    private void reap() {
        connector.withResource(jedis -> {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().match("pillar:inbox:*").count(100);

            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                List<String> keys = result.getResult();
                for (String key : keys) {
                    processInboxKey(jedis, key);
                }
                cursor = result.getCursor();
            } while (!cursor.equals(ScanParams.SCAN_POINTER_START));

            return null;
        });
    }

    private void processInboxKey(Jedis jedis, String inboxKey) {
        // inboxKey format: pillar:inbox:<id>
        String prefix = "pillar:inbox:";
        if (!inboxKey.startsWith(prefix)) {
            return;
        }

        String idStr = inboxKey.substring(prefix.length());
        ServerId id = new ServerId(idStr);

        // 1. Check memory-level gate (cachedFleet)
        if (presence.cachedFleet().contains(id)) {
            return; // Node is alive in the cached fleet
        }

        // 2. Atomic check-and-delete via Lua
        String presenceKey = RedisKeys.presence(id);
        String attemptsKey = RedisKeys.attempts(id);

        try {
            Object result = jedis.eval(LUA_SCRIPT, 3, presenceKey, inboxKey, attemptsKey);
            if (result instanceof Long && ((Long) result) == 1L) {
                logger.info("Reaped abandoned inbox and attempts for dead node: " + id.value());
            }
        } catch (Exception e) {
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
