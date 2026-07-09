package com.markineo.pillar.redis;

import com.google.gson.Gson;
import com.markineo.pillar.core.health.HealthProvider;
import com.markineo.pillar.core.health.HealthSnapshot;
import com.markineo.pillar.core.identity.ServerId;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthIntegrationTest extends RedisIntegrationTest {

    @Test
    void publishedHealthCanBeReadByView() {
        ServerId id = new ServerId("test-node-1");
        Gson gson = new Gson();
        HealthProvider mockProvider = () -> new HealthSnapshot(45.5, 1024L, 4096L, 10, 3, 5);

        HealthService service = new HealthService(connector, id, mockProvider, gson, executors, logger);
        HealthView view = new HealthView(connector, gson);

        service.start();
        try {
            // Wait for the first tick to publish health
            await("health to be published", () -> view.fetch(id).isPresent());

            Optional<HealthSnapshot> snapshotOpt = view.fetch(id);
            assertTrue(snapshotOpt.isPresent());

            HealthSnapshot snapshot = snapshotOpt.get();
            assertEquals(45.5, snapshot.mspt());
            assertEquals(1024L, snapshot.usedMemory());
            assertEquals(4096L, snapshot.maxMemory());
            assertEquals(10, snapshot.players());
            assertEquals(3, snapshot.worlds());
            assertEquals(5, snapshot.pendingSignals());

            // Test fetchAll
            var map = view.fetchAll(Set.of(id, new ServerId("missing")));
            assertEquals(1, map.size());
            assertTrue(map.containsKey(id));
            assertEquals(10, map.get(id).players());

        } finally {
            service.close();
        }
    }
}
