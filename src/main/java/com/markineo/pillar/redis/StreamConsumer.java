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
import java.util.function.Consumer;

public final class StreamConsumer implements AutoCloseable {

    private static final int BLOCK_MILLIS = 2000;
    private static final int MAX_ENTRIES_PER_READ = 32;
    private static final long BACKOFF_MILLIS = 1000;

    private final RedisConnector connector;
    private final EnvelopeCodec codec;
    private final ServerId self;
    private final PillarExecutors executors;
    private final PillarLogger logger;
    private final Consumer<Envelope> sink;
    private final String inbox;

    private volatile boolean running;
    private ExecutorService worker;

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
        this.worker = executors.newSingleThread("stream-consumer");
        worker.submit(this::runLoop);
    }

    @Override
    public void close() {
        this.running = false;
        if (worker != null) {
            worker.shutdownNow();
        }
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
        List<StreamEntryID> settled = new ArrayList<>();
        for (StreamEntry entry : entries) {
            if (isSettled(entry)) {
                settled.add(entry.getID());
            }
        }
        acknowledge(settled);
    }

    // Settled means the entry can be acked: it was delivered to the sink, or it is a
    // permanently undecodable poison entry we choose to drop. An unsettled entry (a handler
    // failure that may be transient) stays pending and is recovered later (PIL-40), not lost.
    private boolean isSettled(StreamEntry entry) {
        Envelope envelope;
        try {
            envelope = decodeEntry(entry);
        } catch (PillarException poison) {
            logger.warn("Discarding undecodable inbox entry " + entry.getID() + ": " + poison.getMessage());
            return true;
        }
        try {
            sink.accept(envelope);
            return true;
        } catch (RuntimeException handlerFailure) {
            logger.error("Handler failed for inbox entry " + entry.getID() + "; left pending.", handlerFailure);
            return false;
        }
    }

    private Envelope decodeEntry(StreamEntry entry) {
        String wire = entry.getFields().get(StreamProtocol.FIELD_DATA);
        if (wire == null) {
            throw new PillarException("stream entry has no '" + StreamProtocol.FIELD_DATA + "' field");
        }
        return codec.decode(wire);
    }

    private void acknowledge(List<StreamEntryID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        try (Jedis jedis = connector.pool().getResource()) {
            jedis.xack(inbox, StreamProtocol.CONSUMER_GROUP, ids.toArray(new StreamEntryID[0]));
        } catch (JedisException e) {
            // A failed ack (Redis blipped after the batch was processed) is safe to log and
            // move on: the entries stay in the pending list and are reclaimed later (PIL-40).
            // Aborting the loop would not help re-ack, and the next read drives state handling.
            logger.warn("Failed to ack " + ids.size() + " inbox entries; left pending: " + e.getMessage());
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
