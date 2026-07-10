package com.markineo.pillar.redis;

import com.markineo.pillar.concurrent.PillarExecutors;
import com.markineo.pillar.config.RedisSettings;
import com.markineo.pillar.logger.PillarLogger;
import com.markineo.pillar.redis.lifecycle.RedisConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class RedisPoolExhaustionIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final int MAX_TOTAL = 2;
    private static final Duration MAX_WAIT = Duration.ofMillis(300);

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(REDIS_PORT);

    private RedisConnector connector;

    @BeforeEach
    void startConnector() {
        PillarLogger logger = new PillarLogger(LoggerFactory.getLogger("pillar-pool-test"));
        PillarExecutors executors = new PillarExecutors(logger);
        RedisSettings settings = new RedisSettings(
                REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT), "", MAX_TOTAL, MAX_WAIT);
        connector = new RedisConnector(settings, executors, logger);
        connector.start();
    }

    @AfterEach
    void stopConnector() {
        connector.close();
    }

    // Guards the regression the default JedisPoolConfig invited: maxWait -1 makes an
    // exhausted pool block the caller forever. With a bounded maxWait the caller must
    // give up near the window and surface a JedisException, letting the control plane
    // degrade instead of deadlock under a connection storm.
    @Test
    void exhaustedPoolFailsFastInsteadOfBlockingForever() {
        List<Jedis> held = new ArrayList<>();
        for (int i = 0; i < MAX_TOTAL; i++) {
            held.add(connector.pool().getResource());
        }

        try {
            long start = System.nanoTime();
            assertThrows(JedisException.class, () -> connector.pool().getResource());
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertTrue(elapsedMillis < MAX_WAIT.toMillis() + 2000,
                    "Exhausted getResource should fail near maxWait, not block; took " + elapsedMillis + "ms");
        } finally {
            held.forEach(Jedis::close);
        }
    }
}
