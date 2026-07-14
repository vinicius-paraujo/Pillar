package br.com.markineo.pillar.redis.routing;

import br.com.markineo.pillar.api.Routing;
import br.com.markineo.pillar.concurrent.PlatformScheduler;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.task.EnvelopeCodec;
import br.com.markineo.pillar.redis.presence.FleetView;
import br.com.markineo.pillar.redis.transport.RequestSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RoutingValidationTest {

    private ExecutorService pool;
    private Routing routing;

    @BeforeEach
    void setUp() {
        pool = Executors.newSingleThreadExecutor();
        PlatformScheduler mainThreadScheduler = new PlatformScheduler() {
            @Override public void runSync(Runnable task) { task.run(); }
            @Override public boolean isMainThread() { return true; }
        };
        
        routing = new RoutingImpl(null, null, null, mainThreadScheduler, pool, new ServerId("test-server"), "proxy");
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void testMoveToServerValidation() {
        UUID id = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> routing.moveToServer(null, "server1"));
        assertThrows(NullPointerException.class, () -> routing.moveToServer(id, null));
        assertThrows(IllegalArgumentException.class, () -> routing.moveToServer(id, " "));
    }

    @Test
    void testMoveToRoleValidation() {
        UUID id = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> routing.moveToRole(null, "role1"));
        assertThrows(NullPointerException.class, () -> routing.moveToRole(id, null));
        assertThrows(IllegalArgumentException.class, () -> routing.moveToRole(id, " "));
    }

    @Test
    void testGuardPropagation() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> routing.moveToServer(id, "target").join());
        assertThrows(IllegalStateException.class, () -> routing.moveToRole(id, "target").join());
    }
}
