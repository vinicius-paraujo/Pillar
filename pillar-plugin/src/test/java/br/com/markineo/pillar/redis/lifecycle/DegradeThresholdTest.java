package br.com.markineo.pillar.redis.lifecycle;

import br.com.markineo.pillar.concurrent.PillarExecutors;
import br.com.markineo.pillar.config.RedisSettings;
import br.com.markineo.pillar.logger.PillarLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

// DEGRADED is not a report, it is a switch: withResource refuses every caller while it
// holds, so one slow sample from the health loop used to refuse work the store could
// still have served. Observed on a real fleet in PIL-156, where a sub-second stall cost
// a player their island. Needs no Redis: a closed port fails the probe just as well.
class DegradeThresholdTest {

    private PillarLogger logger;
    private PillarExecutors executors;
    private RedisConnector connector;

    @BeforeEach
    void setUp() throws IOException {
        logger = new PillarLogger(LoggerFactory.getLogger("pillar-test"));
        executors = new PillarExecutors(logger);

        RedisSettings settings = new RedisSettings(
                "127.0.0.1", closedPort(), "",
                4, Duration.ofMillis(500),
                Duration.ofSeconds(30), 30_000);

        connector = new RedisConnector(settings, executors, logger);
    }

    @AfterEach
    void tearDown() {
        if (connector != null) {
            connector.close();
        }
    }

    @Test
    void oneFailedProbeDoesNotDegrade() {
        // start() probes once synchronously before scheduling the loop, so by the time it
        // returns exactly one failure has been recorded.
        connector.start();

        assertNotEquals(ConnectionState.DEGRADED, connector.state(),
                "a single probe failure declared an outage and shut every caller out");
        assertEquals(ConnectionState.STARTING, connector.state());
    }

    private int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
