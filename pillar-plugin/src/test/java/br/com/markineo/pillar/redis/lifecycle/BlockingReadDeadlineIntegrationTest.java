package br.com.markineo.pillar.redis.lifecycle;

import br.com.markineo.pillar.redis.RedisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Jedis lifts the socket read timeout for blocking commands, so the pool's own timeout
// says nothing about what happens on the consumer's XREADGROUP. These two tests pin the
// two halves of the property the pool has to hold: a blocking read has a deadline, and
// that deadline leaves the consumer's block window room to complete (PIL-150).
class BlockingReadDeadlineIntegrationTest extends RedisIntegrationTest {

    private static final String INBOX = "pillar:inbox:deadline-probe";
    private static final String GROUP = "pillar";
    private static final String CONSUMER = "deadline-probe";

    @BeforeEach
    void createGroup() {
        try (Jedis jedis = connector.getResource()) {
            jedis.xgroupCreate(INBOX, GROUP, StreamEntryID.XGROUP_LAST_ENTRY, true);
        }
    }

    @Test
    void blockWindowInUseCompletesWithinTheDeadline() {
        long start = System.nanoTime();

        List<Map.Entry<String, List<StreamEntry>>> streams;
        try (Jedis jedis = connector.getResource()) {
            streams = readBlocking(jedis, 2000);
        }

        // An empty read is Redis answering, which is the point: the client waited it out
        // instead of tearing the connection down first.
        assertNull(streams);
        // Redis can end a BLOCK a hair early, so this tolerates a small margin. What it is
        // guarding against is the client tearing the connection down mid-window, which
        // would land nowhere near this number.
        assertTrue(elapsedMillis(start) >= 1900, "the block window was cut short");
    }

    @Test
    void blockingReadBeyondTheDeadlineFails() {
        long start = System.nanoTime();

        JedisConnectionException failure;
        try (Jedis jedis = connector.getResource()) {
            failure = assertThrows(JedisConnectionException.class, () -> readBlocking(jedis, 30_000));
        }

        assertInstanceOf(SocketTimeoutException.class, failure.getCause());
        assertTrue(elapsedMillis(start) < 30_000,
                "a stalled blocking read has no deadline and would hold the consumer thread");
    }

    private List<Map.Entry<String, List<StreamEntry>>> readBlocking(Jedis jedis, int blockMillis) {
        XReadGroupParams params = XReadGroupParams.xReadGroupParams().count(32).block(blockMillis);
        return jedis.xreadGroup(GROUP, CONSUMER, params,
                Map.of(INBOX, StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY));
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
