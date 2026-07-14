package br.com.markineo.pillar.api;

import br.com.markineo.pillar.core.lease.Lease;
import br.com.markineo.pillar.core.lease.ResourceKey;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface Leases {
    CompletableFuture<Optional<Lease>> acquire(ResourceKey resource, Duration ttl);
    CompletableFuture<Boolean> renew(Lease lease, Duration ttl);
    CompletableFuture<Boolean> release(Lease lease);
}
