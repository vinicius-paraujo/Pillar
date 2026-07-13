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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

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
    
    private final Set<ServerId> orphanCandidates = ConcurrentHashMap.newKeySet();
    private final AtomicLong reapCount = new AtomicLong(0);

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

    public long reapCount() {
        return reapCount.get();
    }

    private void reap() {
        connector.withResource(jedis -> {
            Set<ServerId> discovered = new HashSet<>();

            scanInboxes(jedis, (key, id) -> {
                discovered.add(id);
                processInbox(jedis, key, id);
            });

            discardMissingCandidates(discovered);

            return null;
        });
    }

    private void scanInboxes(Jedis jedis, BiConsumer<String, ServerId> consumer) {
        String cursor = ScanParams.SCAN_POINTER_START;
        ScanParams params = new ScanParams()
                .match(RedisKeys.inboxPattern())
                .count(100);

        do {
            ScanResult<String> result = jedis.scan(cursor, params);

            for (String key : result.getResult()) {
                ServerId id = RedisKeys.parseInboxId(key);

                if (id != null) {
                    consumer.accept(key, id);
                }
            }

            cursor = result.getCursor();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
    }

    private void discardMissingCandidates(Set<ServerId> discovered) {
        orphanCandidates.removeIf(id -> !discovered.contains(id));
    }

    private void processInbox(Jedis jedis, String inboxKey, ServerId id) {
        if (presence.cachedFleet().contains(id)) {
            orphanCandidates.remove(id);
            return;
        }

        if (!orphanCandidates.add(id)) {
            tryReapInbox(jedis, inboxKey, id);
        }
    }

    private void tryReapInbox(Jedis jedis, String inboxKey, ServerId id) {
        try {
            Object result = jedis.eval(
                    LUA_SCRIPT,
                    3,
                    RedisKeys.presence(id),
                    inboxKey,
                    RedisKeys.attempts(id));

            if (Long.valueOf(1).equals(result)) {
                logger.info("Reaped abandoned inbox and attempts for dead node: " + id.value());
                reapCount.incrementAndGet();
                orphanCandidates.remove(id);
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
