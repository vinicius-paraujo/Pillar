package br.com.markineo.pillar.redis.lease;

import br.com.markineo.pillar.api.lease.Lease;
import br.com.markineo.pillar.core.lease.OwnerToken;
import br.com.markineo.pillar.api.lease.ResourceKey;
import br.com.markineo.pillar.redis.RedisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisLeaseServiceTest extends RedisIntegrationTest {

    private RedisLeaseService service;

    @BeforeEach
    void setupService() {
        service = new RedisLeaseService(connector);
    }

    @Test
    void testAcquireRenewRelease() {
        ResourceKey key = new ResourceKey("test-resource");
        OwnerToken owner = new OwnerToken("test-owner");

        // 1. Acquire
        Optional<Lease> leaseOpt = service.acquire(key, owner, Duration.ofSeconds(5));
        assertTrue(leaseOpt.isPresent(), "Should acquire a free lease");
        Lease lease = leaseOpt.get();
        assertEquals(key, lease.resource());

        // 2. Renew
        boolean renewed = service.renew(lease, Duration.ofSeconds(5), owner);
        assertTrue(renewed, "Should renew an active lease");

        // 3. Release
        boolean released = service.release(lease, owner);
        assertTrue(released, "Should release an active lease");

        // 4. Renew/release after it is already released
        assertFalse(service.renew(lease, Duration.ofSeconds(5), owner), "Renew should fail after release");
        assertFalse(service.release(lease, owner), "Release should fail if already released");
    }

    @Test
    void testContendedAcquire() {
        ResourceKey key = new ResourceKey("contended-resource");
        OwnerToken owner1 = new OwnerToken("owner-1");
        OwnerToken owner2 = new OwnerToken("owner-2");

        // Owner 1 acquires
        Optional<Lease> lease1Opt = service.acquire(key, owner1, Duration.ofSeconds(5));
        assertTrue(lease1Opt.isPresent());

        // Owner 2 tries to acquire and fails
        Optional<Lease> lease2Opt = service.acquire(key, owner2, Duration.ofSeconds(5));
        assertFalse(lease2Opt.isPresent(), "Owner 2 should not acquire a locked resource");

        // Owner 1 releases
        assertTrue(service.release(lease1Opt.get(), owner1));

        // Owner 2 tries again and succeeds
        Optional<Lease> lease3Opt = service.acquire(key, owner2, Duration.ofSeconds(5));
        assertTrue(lease3Opt.isPresent(), "Owner 2 should acquire after owner 1 releases");
    }

    @Test
    void testWrongOwnerRenewRelease() {
        ResourceKey key = new ResourceKey("wrong-owner-resource");
        OwnerToken legitOwner = new OwnerToken("legit-owner");
        OwnerToken wrongOwner = new OwnerToken("wrong-owner");

        // Legit owner acquires
        Optional<Lease> leaseOpt = service.acquire(key, legitOwner, Duration.ofSeconds(5));
        assertTrue(leaseOpt.isPresent());

        // Get the legit lease
        Lease legitLease = leaseOpt.get();

        // Wrong owner tries to renew/release
        assertFalse(service.renew(legitLease, Duration.ofSeconds(5), wrongOwner), "Wrong owner should not be able to renew");
        assertFalse(service.release(legitLease, wrongOwner), "Wrong owner should not be able to release");

        // Legit owner can still release
        assertTrue(service.release(legitLease, legitOwner));
    }

    @Test
    void testTtlExpiry() throws InterruptedException {
        ResourceKey key = new ResourceKey("expiry-resource");
        OwnerToken owner1 = new OwnerToken("owner-1");
        OwnerToken owner2 = new OwnerToken("owner-2");

        // Owner 1 acquires with very short TTL
        Optional<Lease> lease1Opt = service.acquire(key, owner1, Duration.ofMillis(200));
        assertTrue(lease1Opt.isPresent());

        // Owner 2 tries immediately and fails
        assertFalse(service.acquire(key, owner2, Duration.ofSeconds(5)).isPresent());

        // Wait for TTL to expire
        Thread.sleep(300);

        // Owner 2 tries again and succeeds
        Optional<Lease> lease2Opt = service.acquire(key, owner2, Duration.ofSeconds(5));
        assertTrue(lease2Opt.isPresent(), "Owner 2 should acquire after owner 1's lease expires naturally");

        // Owner 1 tries to renew its expired lease - should fail
        assertFalse(service.renew(lease1Opt.get(), Duration.ofSeconds(5), owner1), "Owner 1 should not renew an expired/stolen lease");
        
        // Owner 1 tries to release its expired lease - should fail
        assertFalse(service.release(lease1Opt.get(), owner1), "Owner 1 should not release a stolen lease");
    }
}
