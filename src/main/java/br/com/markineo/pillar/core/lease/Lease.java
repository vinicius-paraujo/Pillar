package br.com.markineo.pillar.core.lease;

/**
 * Proof of ownership over {@code resource}, held until its TTL expires or it is
 * released. Returned by {@link br.com.markineo.pillar.api.Leases#acquire}; pass it
 * back to {@link br.com.markineo.pillar.api.Leases#renew} or
 * {@link br.com.markineo.pillar.api.Leases#release} to act on the same hold.
 *
 * @param resource the locked resource
 * @param owner the token identifying who holds it
 */
public record Lease(ResourceKey resource, OwnerToken owner) {
}
