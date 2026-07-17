package br.com.markineo.pillar.core.lease;

import br.com.markineo.pillar.api.lease.Lease;
import br.com.markineo.pillar.api.lease.ResourceKey;
import java.time.Duration;
import java.util.Optional;

/**
 * A generic lease primitive providing mutual exclusion for resources.
 * This lease mechanism provides check-then-act atomicity (e.g., owner-checked renew and release)
 * against Redis, but issues <strong>no fencing token</strong>.
 * A holder that is stalled past the lease TTL (due to a GC pause, clock drift, etc.)
 * might still attempt to act on the resource while a new owner already holds the lease.
 * Therefore, a lease alone must not guard a non-idempotent external effect unless paired
 * with idempotency or a fencing-aware resource.
 */
public interface LeaseService {

    /**
     * Attempts to acquire a lease for the given resource.
     * 
     * @param resource the resource to acquire
     * @param owner the owner token representing the caller
     * @param ttl the initial time-to-live for the lease
     * @return an {@code Optional} containing the {@link Lease} if acquired, or empty if the resource is already locked
     */
    Optional<Lease> acquire(ResourceKey resource, OwnerToken owner, Duration ttl);

    /**
     * Attempts to renew an existing lease.
     * 
     * @param lease the lease to renew
     * @param newTtl the new time-to-live for the lease
     * @param owner the owner token representing the caller
     * @return {@code true} if successfully renewed, {@code false} if the owner has changed or the lease expired
     */
    boolean renew(Lease lease, Duration newTtl, OwnerToken owner);

    /**
     * Attempts to release an existing lease.
     * 
     * @param lease the lease to release
     * @param owner the owner token representing the caller
     * @return {@code true} if successfully released, {@code false} if the owner has changed or the lease expired
     */
    boolean release(Lease lease, OwnerToken owner);
}
