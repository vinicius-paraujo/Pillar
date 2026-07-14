package br.com.markineo.pillar.redis.lease;

import br.com.markineo.pillar.api.Leases;
import br.com.markineo.pillar.concurrent.PillarExecutors;
import br.com.markineo.pillar.concurrent.PlatformScheduler;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.lease.Lease;
import br.com.markineo.pillar.core.lease.ResourceKey;
import br.com.markineo.pillar.logger.PillarLogger;
import br.com.markineo.pillar.redis.RedisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class LeasesIntegrationTest extends RedisIntegrationTest {

    private ExecutorService ioPool;
    private Leases leasesAlpha;
    private Leases leasesBeta;

    @BeforeEach
    void setUpLocal() {
        PillarLogger logger = new PillarLogger(org.slf4j.LoggerFactory.getLogger(LeasesIntegrationTest.class));
        PillarExecutors executors = new PillarExecutors(logger);
        ioPool = executors.newBoundedWorkerPool("test-io", 4, 100);

        PlatformScheduler dummyScheduler = new PlatformScheduler() {
            @Override
            public void runSync(Runnable task) { task.run(); }
            @Override
            public boolean isMainThread() { return false; }
        };

        RedisLeaseService baseService = new RedisLeaseService(connector);
        
        leasesAlpha = new LeasesImpl(baseService, ioPool, dummyScheduler, new ServerId("alpha"));
        leasesBeta = new LeasesImpl(baseService, ioPool, dummyScheduler, new ServerId("beta"));
    }

    @AfterEach
    void tearDownLocal() {
        ioPool.shutdownNow();
    }

    @Test
    void testContentionAndOwnerChecks() {
        ResourceKey key = new ResourceKey("my-test-resource");
        Duration ttl = Duration.ofSeconds(5);

        // Alpha acquires successfully
        Optional<Lease> alphaLease = leasesAlpha.acquire(key, ttl).join();
        assertTrue(alphaLease.isPresent(), "Alpha should acquire the lease");

        // Beta attempts to acquire and fails (contention)
        Optional<Lease> betaLease = leasesBeta.acquire(key, ttl).join();
        assertFalse(betaLease.isPresent(), "Beta should not acquire the lease while Alpha holds it");

        // Alpha renews successfully
        boolean renewed = leasesAlpha.renew(alphaLease.get(), ttl).join();
        assertTrue(renewed, "Alpha should be able to renew its own lease");

        // Beta attempts to release Alpha's lease and fails
        boolean releasedByBeta = leasesBeta.release(alphaLease.get()).join();
        assertFalse(releasedByBeta, "Beta should not be able to release Alpha's lease");

        // Alpha releases successfully
        boolean releasedByAlpha = leasesAlpha.release(alphaLease.get()).join();
        assertTrue(releasedByAlpha, "Alpha should be able to release its own lease");

        // Beta acquires successfully now that it's released
        Optional<Lease> betaLease2 = leasesBeta.acquire(key, ttl).join();
        assertTrue(betaLease2.isPresent(), "Beta should acquire the lease after Alpha released it");
    }
}
