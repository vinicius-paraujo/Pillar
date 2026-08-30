package br.com.markineo.pillar.redis.lifecycle;

import br.com.markineo.pillar.logger.PillarLogger;
import br.com.markineo.pillar.redis.RedisIntegrationTest;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// A driver failure and a Redis that simply answered no both leave withResource returning
// an empty Optional, so the only thing that can tell them apart afterwards is the log
// (PIL-148). Locks that the record exists and that it stays one line while a store keeps
// failing every borrow.
class WithResourceFailureLoggingIntegrationTest extends RedisIntegrationTest {

    private static final String MESSAGE_PREFIX = "Redis command failed";

    @Test
    void aSwallowedDriverFailureIsRecordedOnce() {
        Optional<String> result = failOnce();

        assertTrue(result.isEmpty(), "the caller still sees an empty result");
        assertEquals(1, recordedFailures().size(), "the driver failure reached no log");

        failOnce();
        failOnce();

        assertEquals(1, recordedFailures().size(),
                "a store failing every borrow produces one line per call");
    }

    private Optional<String> failOnce() {
        return connector.withResource(jedis -> {
            throw new JedisConnectionException("probe failure");
        });
    }

    private List<PillarLogger.Entry> recordedFailures() {
        return logger.history().stream()
                .filter(entry -> entry.level() == PillarLogger.Level.WARN)
                .filter(entry -> entry.message().startsWith(MESSAGE_PREFIX))
                .toList();
    }
}
