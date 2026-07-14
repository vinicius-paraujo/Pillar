package br.com.markineo.pillar.redis.lease;

import br.com.markineo.pillar.api.Leases;
import br.com.markineo.pillar.concurrent.PlatformScheduler;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.lease.Lease;
import br.com.markineo.pillar.core.lease.LeaseService;
import br.com.markineo.pillar.core.lease.OwnerToken;
import br.com.markineo.pillar.core.lease.ResourceKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertThrows;

class LeasesValidationTest {

    private ExecutorService pool;
    private Leases leases;

    @BeforeEach
    void setUp() {
        pool = Executors.newSingleThreadExecutor();
        // Reports the main thread so the anti-join guard fires deterministically.
        PlatformScheduler mainThreadScheduler = new PlatformScheduler() {
            @Override public void runSync(Runnable task) { task.run(); }
            @Override public boolean isMainThread() { return true; }
        };

        LeaseService dummyService = new LeaseService() {
            @Override public Optional<Lease> acquire(ResourceKey resource, OwnerToken owner, Duration ttl) { return Optional.empty(); }
            @Override public boolean renew(Lease lease, Duration ttl) { return false; }
            @Override public boolean release(Lease lease) { return false; }
        };

        leases = new LeasesImpl(dummyService, pool, mainThreadScheduler, new ServerId("test-server"));
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void testAcquireValidation() {
        assertThrows(NullPointerException.class, () -> leases.acquire(null, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> leases.acquire(new ResourceKey(" "), Duration.ofSeconds(5)));
        assertThrows(NullPointerException.class, () -> leases.acquire(new ResourceKey("res"), null));
        assertThrows(IllegalArgumentException.class, () -> leases.acquire(new ResourceKey("res"), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> leases.acquire(new ResourceKey("res"), Duration.ofSeconds(-1)));
    }

    @Test
    void testRenewValidation() {
        Lease dummyLease = new Lease(new ResourceKey("res"), new OwnerToken("owner"));
        assertThrows(NullPointerException.class, () -> leases.renew(null, Duration.ofSeconds(5)));
        assertThrows(NullPointerException.class, () -> leases.renew(dummyLease, null));
        assertThrows(IllegalArgumentException.class, () -> leases.renew(dummyLease, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> leases.renew(dummyLease, Duration.ofSeconds(-1)));
    }

    @Test
    void testReleaseValidation() {
        assertThrows(NullPointerException.class, () -> leases.release(null));
    }

    @Test
    void testGuardPropagation() {
        ResourceKey key = new ResourceKey("valid");
        Duration ttl = Duration.ofSeconds(5);

        assertThrows(IllegalStateException.class, () -> leases.acquire(key, ttl).join());

        Lease dummyLease = new Lease(key, new OwnerToken("owner"));
        assertThrows(IllegalStateException.class, () -> leases.renew(dummyLease, ttl).join());
        assertThrows(IllegalStateException.class, () -> leases.release(dummyLease).join());
    }
}
