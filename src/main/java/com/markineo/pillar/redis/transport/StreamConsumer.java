package com.markineo.pillar.redis.transport;

import com.markineo.pillar.redis.RedisKeys;
import com.markineo.pillar.redis.lifecycle.RedisConnector;
import com.markineo.pillar.concurrent.PillarExecutors;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.task.Envelope;
import com.markineo.pillar.core.task.EnvelopeCodec;
import com.markineo.pillar.error.PillarException;
import com.markineo.pillar.logger.PillarLogger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

import java.time.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

public final class StreamConsumer implements AutoCloseable {

    private static final int BLOCK_MILLIS = 2000;
    private static final int MAX_ENTRIES_PER_READ = 32;
    private static final long BACKOFF_MILLIS = 1000;
    private static final int MAX_ATTEMPTS = 3;

    private static final int WORKER_POOL_SIZE = 4;
    private static final int WORKER_QUEUE_CAPACITY = 128;

    private final RedisConnector connector;
    private final EnvelopeCodec codec;
    private final ServerId self;
    private final PillarExecutors executors;
    private final PillarLogger logger;
    private final Consumer<Envelope> sink;
    private final String inbox;
    private final Duration dedupWindow;

    private volatile boolean running;
    private ExecutorService worker;
    private ThreadPoolExecutor dispatchPool;

    public StreamConsumer(RedisConnector connector, EnvelopeCodec codec, ServerId self,
                          PillarExecutors executors, PillarLogger logger, Consumer<Envelope> sink, Duration dedupWindow) {
        this.connector = connector;
        this.codec = codec;
        this.self = self;
        this.executors = executors;
        this.logger = logger;
        this.sink = sink;
        this.dedupWindow = dedupWindow;
        this.inbox = RedisKeys.inbox(self);
    }

    public void start() {
        this.running = true;
        this.dispatchPool = executors.newBoundedWorkerPool("dispatch", WORKER_POOL_SIZE, WORKER_QUEUE_CAPACITY);
        this.worker = executors.newSingleThread("stream-consumer");
        worker.submit(this::runLoop);
    }

    @Override
    public void close() {
        this.running = false;
        if (dispatchPool != null) {
            dispatchPool.shutdownNow();
        }
        if (worker != null) {
            worker.shutdownNow();
        }
    }

    public int pendingSignals() {
        return dispatchPool == null ? 0 : dispatchPool.getQueue().size();
    }

    private void runLoop() {
        boolean groupEnsured = false;
        while (running) {
            if (!connector.isReady()) {
                groupEnsured = false;
                if (interruptedDuringBackoff()) {
                    return;
                }

                continue;
            }
            try {
                if (!groupEnsured) {
                    ensureConsumerGroup();
                    groupEnsured = true;
                    drainPendingEntries();
                }
                processBatch(readBatch());
            } catch (JedisException e) {
                // Transient Redis fault; the connector's health loop owns the state
                // transition and logs it once. Re-ensure the group on recovery and back off.
                groupEnsured = false;
                if (interruptedDuringBackoff()) {
                    return;
                }
            }
        }
    }

    private List<StreamEntry> readBatch() {
        try (Jedis jedis = connector.pool().getResource()) {
            XReadGroupParams readParams = XReadGroupParams.xReadGroupParams()
                    .count(MAX_ENTRIES_PER_READ)
                    .block(BLOCK_MILLIS);

            List<Map.Entry<String, List<StreamEntry>>> streams = jedis.xreadGroup(
                    StreamProtocol.CONSUMER_GROUP,
                    self.value(),
                    readParams,
                    Map.of(inbox, StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY));

            return entriesFrom(streams);
        }
    }

    private void drainPendingEntries() {
        StreamEntryID lastId = new StreamEntryID("0-0");

        while (running && connector.isReady()) {
            List<StreamEntry> pending;
            try (Jedis jedis = connector.pool().getResource()) {
                XReadGroupParams readParams = XReadGroupParams.xReadGroupParams().count(MAX_ENTRIES_PER_READ);
                List<Map.Entry<String, List<StreamEntry>>> streams = jedis.xreadGroup(
                        StreamProtocol.CONSUMER_GROUP,
                        self.value(),
                        readParams,
                        Map.of(inbox, lastId));

                pending = entriesFrom(streams);
            } catch (JedisException e) {
                logger.warn("Failed to drain PEL: " + e.getMessage());
                break;
            }

            if (pending.isEmpty()) {
                break;
            }

            for (StreamEntry entry : pending) {
                Envelope envelope = decodeSafe(entry);
                if (envelope == null) continue;

                dispatch(entry, envelope, true);
            }

            lastId = pending.getLast().getID();
        }
    }

    private void processBatch(List<StreamEntry> entries) {
        for (StreamEntry entry : entries) {
            Envelope envelope = decodeSafe(entry);
            if (envelope == null) continue;

            dispatch(entry, envelope, false);
        }
    }

    private Envelope decodeSafe(StreamEntry entry) {
        try {
            return decodeEntry(entry);
        } catch (PillarException poison) {
            logger.warn("Discarding undecodable inbox entry " + entry.getID() + ": " + poison.getMessage());
            dispatchPool.execute(() -> {
                try (Jedis jedis = connector.pool().getResource()) {
                    acknowledgeAndClean(jedis, entry.getID());
                } catch (JedisException ignored) {}
            });
            return null;
        }
    }

    private void dispatch(StreamEntry entry, Envelope envelope, boolean isPel) {
        dispatchPool.execute(() -> {
            try (Jedis jedis = connector.pool().getResource()) {
                long attempt = 1;
                if (isPel) {
                    attempt = jedis.hincrBy(RedisKeys.attempts(self), entry.getID().toString(), 1);
                    if (attempt > MAX_ATTEMPTS) {
                        logger.warn("Dead-lettering inbox entry " + entry.getID() + " after " + attempt + " attempts.");
                        acknowledgeAndClean(jedis, entry.getID());
                        return;
                    }
                }

                String dedupKey = RedisKeys.dedup(self, entry.getID().toString());
                // 30-second processing lock to ensure only one thread/node processes this request if the PEL retries too early
                String lock = jedis.set(dedupKey, "PROCESSING", SetParams.setParams().nx().ex(30));
                if (!"OK".equals(lock)) {
                    String state = jedis.get(dedupKey);
                    if ("DONE".equals(state)) {
                        logger.info("Skipping already processed inbox entry " + entry.getID());
                        acknowledgeAndClean(jedis, entry.getID());
                    } else {
                        logger.info("Skipping currently processing inbox entry " + entry.getID());
                    }
                    return;
                }

                try {
                    sink.accept(envelope);

                    // Successfully processed. Mark for the entire dedup window.
                    jedis.setex(dedupKey, dedupWindow.toSeconds(), "DONE");
                    acknowledgeAndClean(jedis, entry.getID());
                } catch (RuntimeException handlerFailure) {
                    logger.error("Handler failed for inbox entry " + entry.getID() + " (attempt " + attempt + "); left pending.", handlerFailure);
                    jedis.del(dedupKey); // Release lock so it can be retried immediately
                }
            } catch (JedisException e) {
                logger.warn("Redis error while processing entry " + entry.getID() + ": " + e.getMessage());
            }
        });
    }

    private void acknowledgeAndClean(Jedis jedis, StreamEntryID id) {
        jedis.xack(inbox, StreamProtocol.CONSUMER_GROUP, id);
        jedis.hdel(RedisKeys.attempts(self), id.toString());
    }

    private List<StreamEntry> entriesFrom(List<Map.Entry<String, List<StreamEntry>>> streams) {
        if (streams == null) {
            return java.util.List.of();
        }
        List<StreamEntry> entries = new java.util.ArrayList<>();
        for (Map.Entry<String, List<StreamEntry>> stream : streams) {
            entries.addAll(stream.getValue());
        }
        return entries;
    }

    private Envelope decodeEntry(StreamEntry entry) {
        String wire = entry.getFields().get(StreamProtocol.FIELD_DATA);
        if (wire == null) {
            throw new PillarException("stream entry has no '" + StreamProtocol.FIELD_DATA + "' field");
        }
        return codec.decode(wire);
    }

    private void ensureConsumerGroup() {
        try (Jedis jedis = connector.pool().getResource()) {
            // MKSTREAM creates the inbox on first use; XGROUP_LAST_ENTRY ($) means the group
            // only sees messages published after it exists ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â history predating this node is
            // irrelevant to a fresh consumer.
            jedis.xgroupCreate(inbox, StreamProtocol.CONSUMER_GROUP, StreamEntryID.XGROUP_LAST_ENTRY, true);
        } catch (JedisDataException e) {
            if (!isGroupAlreadyExists(e)) {
                throw e;
            }
        }
    }

    private boolean isGroupAlreadyExists(JedisDataException e) {
        return e.getMessage() != null && e.getMessage().contains("BUSYGROUP");
    }

    // Sleeps for the backoff window between attempts; returns true if the thread was
    // interrupted (shutdown), signalling the loop to stop.
    private boolean interruptedDuringBackoff() {
        try {
            Thread.sleep(BACKOFF_MILLIS);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }
}
