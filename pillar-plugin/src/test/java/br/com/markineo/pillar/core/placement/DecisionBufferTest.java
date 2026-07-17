package br.com.markineo.pillar.core.placement;

import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionBufferTest {

    @Test
    void boundsAtLimit() {
        DecisionBuffer buffer = new DecisionBuffer(3);
        
        buffer.add(PlacementDecision.refused(Instant.now(), new ServerRole("hub"), "reason1"));
        buffer.add(PlacementDecision.refused(Instant.now(), new ServerRole("hub"), "reason2"));
        buffer.add(PlacementDecision.refused(Instant.now(), new ServerRole("hub"), "reason3"));
        buffer.add(PlacementDecision.refused(Instant.now(), new ServerRole("hub"), "reason4"));

        List<PlacementDecision> recent = buffer.recent();
        assertEquals(3, recent.size(), "Buffer must not exceed limit");
        assertEquals("reason2", ((PlacementDecision.Refused) recent.get(0)).refusalReason());
        assertEquals("reason4", ((PlacementDecision.Refused) recent.get(2)).refusalReason());
    }

    @Test
    void handlesConcurrentAdds() throws InterruptedException {
        DecisionBuffer buffer = new DecisionBuffer(100);
        int threads = 10;
        int addsPerThread = 20;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                for (int j = 0; j < addsPerThread; j++) {
                    buffer.add(PlacementDecision.success(Instant.now(), new ServerRole("hub"), new ServerId("hub-" + j)));
                }
                latch.countDown();
            });
        }

        latch.await();
        pool.shutdown();

        List<PlacementDecision> recent = buffer.recent();
        assertEquals(100, recent.size(), "Buffer should be exactly at limit under concurrent load");
        for (PlacementDecision decision : recent) {
            assertTrue(decision instanceof PlacementDecision.Success);
        }
    }
}
