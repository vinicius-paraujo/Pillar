package br.com.markineo.pillar.redis.presence;

import br.com.markineo.pillar.redis.RedisIntegrationTest;
import com.google.gson.Gson;
import br.com.markineo.pillar.core.health.HealthProvider;
import br.com.markineo.pillar.core.health.HealthSnapshot;
import br.com.markineo.pillar.core.identity.ServerId;
import org.junit.jupiter.api.Test;

import br.com.markineo.pillar.redis.RedisKeys;
import redis.clients.jedis.Jedis;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthIntegrationTest extends RedisIntegrationTest {

    @Test
    void publishedHealthCanBeReadByView() {
        ServerId id = new ServerId("test-node-1");
        Gson gson = new Gson();
        HealthProvider mockProvider = () -> new HealthSnapshot(45.5, 1024L, 4096L, 10, 3, 5);

        HealthService service = new HealthService(connector, id, mockProvider, gson, executors, logger, java.time.Duration.ofSeconds(5));
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
            var map = view.fetchAll(Set.of(id, new ServerId("missing"))).orElseThrow();
            assertEquals(1, map.size());
            assertTrue(map.containsKey(id));
            assertEquals(10, map.get(id).players());

        } finally {
            service.close();
        }
    }

    @Test
    void poisonNodeIsSkippedWithoutAffectingValidNodes() {
        Gson gson = new Gson();
        HealthView view = new HealthView(connector, gson);

        ServerId valid = new ServerId("valid-node");
        ServerId poison = new ServerId("poison-node");

        try (Jedis jedis = connector.getResource()) {
            jedis.set(RedisKeys.health(valid), "{\"mspt\":20.0,\"usedMemory\":512,\"maxMemory\":1024,\"players\":5,\"worlds\":1,\"pendingSignals\":0}");
            jedis.set(RedisKeys.health(poison), "{\"mspt\":-5,\"usedMemory\":512,\"maxMemory\":1024,\"players\":5,\"worlds\":1,\"pendingSignals\":0}");
        }

        Map<ServerId, HealthSnapshot> result = view.fetchAll(Set.of(valid, poison)).orElseThrow();

        assertEquals(1, result.size(), "Only the valid node should be in the result");
        assertTrue(result.containsKey(valid));
        assertFalse(result.containsKey(poison));
        assertEquals(20.0, result.get(valid).mspt());

        Optional<HealthSnapshot> singlePoison = view.fetch(poison);
        assertTrue(singlePoison.isEmpty(), "fetch() should return empty for a poison node");
    }
}
