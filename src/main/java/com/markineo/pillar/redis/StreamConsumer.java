package com.markineo.pillar.redis;

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
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

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

    private volatile boolean running;
    private ExecutorService worker;
    private ThreadPoolExecutor dispatchPool;

    public StreamConsumer(RedisConnector connector, EnvelopeCodec codec, ServerId self,
                          PillarExecutors executors, PillarLogger logger, Consumer<Envelope> sink) {
        this.connector = connector;
        this.codec = codec;
        this.self = self;
        this.executors = executors;
        this.logger = logger;
        this.sink = sink;
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
        String attemptsKey = RedisKeys.attempts(self);

        while (running && connector.isReady()) {
            try (Jedis jedis = connector.pool().getResource()) {
                XReadGroupParams readParams = XReadGroupParams.xReadGroupParams().count(MAX_ENTRIES_PER_READ);
                List<Map.Entry<String, List<StreamEntry>>> streams = jedis.xreadGroup(
                        StreamProtocol.CONSUMER_GROUP,
                        self.value(),
                        readParams,
                        Map.of(inbox, lastId));

                List<StreamEntry> pending = entriesFrom(streams);
                if (pending.isEmpty()) {
                    break;
                }

                for (StreamEntry entry : pending) {
                    long attempts = jedis.hincrBy(attemptsKey, entry.getID().toString(), 1);

                    if (attempts > MAX_ATTEMPTS) {
                        logger.warn("Dead-lettering inbox entry " + entry.getID() + " after " + attempts + " attempts.");
                        acknowledge(entry.getID());
                        continue;
                    }

                    Envelope envelope;
                    try {
                        envelope = decodeEntry(entry);
                    } catch (PillarException poison) {
                        logger.warn("Discarding undecodable inbox entry " + entry.getID() + ": " + poison.getMessage());
                        acknowledge(entry.getID());
                        continue;
                    }

                    dispatchPool.execute(() -> {
                        try {
                            sink.accept(envelope);
                            acknowledge(entry.getID());
                        } catch (RuntimeException handlerFailure) {
                            logger.error("Handler failed for inbox entry " + entry.getID() + " (attempt " + attempts + "); left pending.", handlerFailure);
                        }
                    });
                }

                lastId = pending.getLast().getID();
            } catch (JedisException e) {
                logger.warn("Failed to drain PEL: " + e.getMessage());
                break;
            }
        }
    }

    // XREADGROUP returns entries grouped by stream; we subscribe to a single inbox, so
    // flatten to that inbox's entries. A null result means the blocking read timed out
    // with nothing new to deliver.
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

    private void processBatch(List<StreamEntry> entries) {
        for (StreamEntry entry : entries) {
            Envelope envelope;
            try {
                envelope = decodeEntry(entry);
            } catch (PillarException poison) {
                logger.warn("Discarding undecodable inbox entry " + entry.getID() + ": " + poison.getMessage());
                acknowledge(entry.getID());
                continue;
            }

            dispatchPool.execute(() -> {
                try {
                    sink.accept(envelope);
                    acknowledge(entry.getID());
                } catch (RuntimeException handlerFailure) {
                    logger.error("Handler failed for inbox entry " + entry.getID() + "; left pending.", handlerFailure);
                }
            });
        }
    }

    private Envelope decodeEntry(StreamEntry entry) {
        String wire = entry.getFields().get(StreamProtocol.FIELD_DATA);
        if (wire == null) {
            throw new PillarException("stream entry has no '" + StreamProtocol.FIELD_DATA + "' field");
        }
        return codec.decode(wire);
    }

    private void acknowledge(StreamEntryID... ids) {
        if (ids.length == 0) {
            return;
        }
        String[] stringIds = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            stringIds[i] = ids[i].toString();
        }

        try (Jedis jedis = connector.pool().getResource()) {
            jedis.xack(inbox, StreamProtocol.CONSUMER_GROUP, ids);
            jedis.hdel(RedisKeys.attempts(self), stringIds);
        } catch (JedisException e) {
            // A failed ack (Redis blipped after the batch was processed) is safe to log and
            // move on: the entries stay in the pending list and are reclaimed later (PIL-40).
            // Aborting the loop would not help re-ack, and the next read drives state handling.
            logger.warn("Failed to ack " + ids.length + " inbox entries; left pending: " + e.getMessage());
        }
    }

    private void ensureConsumerGroup() {
        try (Jedis jedis = connector.pool().getResource()) {
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
