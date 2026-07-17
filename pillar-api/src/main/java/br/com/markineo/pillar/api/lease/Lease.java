package br.com.markineo.pillar.api.lease;

import java.util.Objects;

/**
 * Proof of ownership over {@code resource}, held until its TTL expires or it is
 * released. Returned by {@link br.com.markineo.pillar.api.Leases#acquire}; pass it
 * back to {@link br.com.markineo.pillar.api.Leases#renew} or
 * {@link br.com.markineo.pillar.api.Leases#release} to act on the same hold.
 *
 * @param resource the locked resource
 */
public record Lease(ResourceKey resource) {
    public Lease {
        Objects.requireNonNull(resource, "resource");
    }
}
