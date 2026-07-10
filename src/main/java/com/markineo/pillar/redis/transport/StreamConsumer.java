package com.markineo.pillar.redis.transport;

import com.markineo.pillar.redis.RedisKeys;
import com.markineo.pillar.redis.lifecycle.RedisConnector;
import com.markineo.pillar.concurrent.PillarExecutors;
import com.markineo.pillar.core.health.SignalTracker;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.task.Envelope;
import com.markineo.pillar.core.task.EnvelopeCodec;
import com.markineo.pillar.error.PillarException;
import com.markineo.pillar.logger.PillarLogger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class StreamConsumer implements AutoCloseable, SignalTracker {

    private static final int BLOCK_MILLIS = 2000;
    private static final int MAX_ENTRIES_PER_READ = 32;
    private static final long BACKOFF_MILLIS = 1000;
    private static final int MAX_ATTEMPTS = 3;
    private static final int PROCESSING_LOCK_SECONDS = 30;

    private final RedisConnector connector;
    private final EnvelopeCodec codec;
    private final ServerId self;
    private final PillarExecutors executors;
    private final PillarLogger logger;
    private final Consumer<Envelope> sink;
    private final String inbox;
    private final Duration dedupWindow;
    private final int poolSize;
    private final int queueCapacity;

    private volatile boolean running;
    private ExecutorService worker;
    private ScheduledExecutorService pelDrainer;
    private ThreadPoolExecutor dispatchPool;

    public StreamConsumer(RedisConnector connector, EnvelopeCodec codec, ServerId self,
                          PillarExecutors executors, PillarLogger logger, Consumer<Envelope> sink,
                          Duration dedupWindow, int poolSize, int queueCapacity) {
        this.connector = connector;
        this.codec = codec;
        this.self = self;
        this.executors = executors;
        this.logger = logger;
        this.sink = sink;
        this.dedupWindow = dedupWindow;
        this.poolSize = poolSize;
        this.queueCapacity = queueCapacity;
        this.inbox = RedisKeys.inbox(self);
    }

    public void start() {
        this.running = true;
        this.dispatchPool = executors.newBoundedWorkerPool("dispatch", poolSize, queueCapacity);
        this.worker = executors.newSingleThread("stream-consumer");
        this.pelDrainer = executors.newSingleThreadScheduled("pel-drainer");
        worker.submit(this::runLoop);
        pelDrainer.scheduleWithFixedDelay(this::drainPendingEntries, 30, 30, TimeUnit.SECONDS);
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
        if (pelDrainer != null) {
            pelDrainer.shutdownNow();
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
        try (Jedis jedis = connector.getResource()) {
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
            try (Jedis jedis = connector.getResource()) {
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
                try (Jedis jedis = connector.getResource()) {
                    acknowledgeAndClean(jedis, entry.getID());
                } catch (JedisException e) {
                    logger.warn("Failed to clean poison entry " + entry.getID() + ": " + e.getMessage());
                }
            });
            return null;
        }
    }

    private void dispatch(StreamEntry entry, Envelope envelope, boolean isPel) {
        dispatchPool.execute(() -> processSafely(entry, envelope, isPel));
    }

    private void processSafely(StreamEntry entry, Envelope envelope, boolean isPel) {
        try (Jedis jedis = connector.getResource()) {
            long attempt = trackAttempt(jedis, entry, isPel);
            if (attempt > MAX_ATTEMPTS) {
                return;
            }

            if (!acquireProcessingLock(jedis, entry)) {
                return;
            }

            executeHandler(jedis, entry, envelope, attempt);
        } catch (JedisException e) {
            logger.warn("Redis error while processing entry " + entry.getID() + ": " + e.getMessage());
        }
    }

    private long trackAttempt(Jedis jedis, StreamEntry entry, boolean isPel) {
        if (!isPel) {
            return 1;
        }
        long attempt = jedis.hincrBy(RedisKeys.attempts(self), entry.getID().toString(), 1);
        if (attempt > MAX_ATTEMPTS) {
            logger.warn("Dead-lettering inbox entry " + entry.getID() + " after " + attempt + " attempts.");
            acknowledgeAndClean(jedis, entry.getID());
        }
        return attempt;
    }

    private boolean acquireProcessingLock(Jedis jedis, StreamEntry entry) {
        String dedupKey = RedisKeys.dedup(self, entry.getID().toString());
        String lock = jedis.set(dedupKey, "PROCESSING", SetParams.setParams().nx().ex(PROCESSING_LOCK_SECONDS));
        if ("OK".equals(lock)) {
            return true;
        }

        String state = jedis.get(dedupKey);
        if ("DONE".equals(state)) {
            logger.info("Skipping already processed inbox entry " + entry.getID());
            acknowledgeAndClean(jedis, entry.getID());
        } else {
            logger.info("Skipping currently processing inbox entry " + entry.getID());
        }
        return false;
    }

    private void executeHandler(Jedis jedis, StreamEntry entry, Envelope envelope, long attempt) {
        String dedupKey = RedisKeys.dedup(self, entry.getID().toString());
        try {
            sink.accept(envelope);
            try (Pipeline pipeline = jedis.pipelined()) {
                pipeline.setex(dedupKey, dedupWindow.toSeconds(), "DONE");
                acknowledgeAndClean(pipeline, entry.getID());
                pipeline.sync();
            }
        } catch (RuntimeException handlerFailure) {
            logger.error("Handler failed for inbox entry " + entry.getID() + " (attempt " + attempt + "); left pending.", handlerFailure);
            jedis.del(dedupKey); // Release lock so it can be retried immediately
        }
    }

    private void acknowledgeAndClean(Jedis jedis, StreamEntryID id) {
        try (Pipeline pipeline = jedis.pipelined()) {
            acknowledgeAndClean(pipeline, id);
            pipeline.sync();
        }
    }

    private void acknowledgeAndClean(Pipeline pipeline, StreamEntryID id) {
        pipeline.xack(inbox, StreamProtocol.CONSUMER_GROUP, id);
        pipeline.hdel(RedisKeys.attempts(self), id.toString());
    }

    private List<StreamEntry> entriesFrom(List<Map.Entry<String, List<StreamEntry>>> streams) {
        if (streams == null) {
            return List.of();
        }
        List<StreamEntry> entries = new ArrayList<>();
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
        try (Jedis jedis = connector.getResource()) {
            // MKSTREAM creates the inbox on first use; XGROUP_LAST_ENTRY ($) means the group
            // only sees messages published after it exists — history predating this node is
            // irrelevant to a fresh consumer.
            jedis.xgroupCreate(inbox, StreamProtocol.CONSUMER_GROUP, StreamEntryID.XGROUP_LAST_ENTRY, true);
        } catch (JedisDataException e) {
            if (!isGroupAlreadyExists(e)) {
                throw e;
            }
        }
    }

    private boolean isGroupAlreadyExists(JedisDataException e) {
        return e.getMessage() != null && e.getMessage().startsWith("BUSYGROUP");
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
