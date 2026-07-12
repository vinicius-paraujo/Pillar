package br.com.markineo.pillar.redis;

import br.com.markineo.pillar.redis.lifecycle.RedisConnector;
import br.com.markineo.pillar.concurrent.PillarExecutors;
import br.com.markineo.pillar.config.RedisSettings;
import br.com.markineo.pillar.logger.PillarLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

@Testcontainers(disabledWithoutDocker = true)
public abstract class RedisIntegrationTest {

    private static final int REDIS_PORT = 6379;

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(REDIS_PORT);

    protected PillarLogger logger;
    protected PillarExecutors executors;
    protected RedisConnector connector;

    @BeforeEach
    void startConnector() {
        logger = new PillarLogger(LoggerFactory.getLogger("pillar-test"));
        executors = new PillarExecutors(logger);

        RedisSettings settings = new RedisSettings(
                REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT), "", 16, Duration.ofMillis(2000), Duration.ofSeconds(30));
        connector = new RedisConnector(settings, executors, logger);
        connector.start();
        awaitReady();

        try (Jedis jedis = connector.getResource()) {
            jedis.flushAll();
        }
    }

    @AfterEach
    void stopConnector() {
        connector.close();
    }

    private void awaitReady() {
        await("Redis connection never became READY", connector::isReady);
    }

    protected void await(String message, BooleanSupplier condition) {
        Duration limit = Duration.ofSeconds(5);
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
        fail(message);
    }

    private void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
