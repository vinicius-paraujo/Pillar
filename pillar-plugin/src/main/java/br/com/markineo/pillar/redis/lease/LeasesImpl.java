package br.com.markineo.pillar.redis.lease;

import br.com.markineo.pillar.api.Leases;
import br.com.markineo.pillar.concurrent.PillarFutures;
import br.com.markineo.pillar.concurrent.PlatformScheduler;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.api.lease.Lease;
import br.com.markineo.pillar.core.lease.LeaseService;
import br.com.markineo.pillar.core.lease.OwnerToken;
import br.com.markineo.pillar.api.lease.ResourceKey;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class LeasesImpl implements Leases {

    private final LeaseService delegate;
    private final ExecutorService ioPool;
    private final PlatformScheduler scheduler;
    private final ServerId selfId;
    private final String sessionSuffix;

    public LeasesImpl(LeaseService delegate, ExecutorService ioPool, PlatformScheduler scheduler, ServerId selfId) {
        this.delegate = delegate;
        this.ioPool = ioPool;
        this.scheduler = scheduler;
        this.selfId = selfId;
        this.sessionSuffix = UUID.randomUUID().toString().substring(0, 8);
    }

    private OwnerToken createOwnerToken() {
        return new OwnerToken(selfId.value() + "-" + sessionSuffix);
    }

    private static void validateTtl(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be greater than zero");
        }
    }

    @Override
    public CompletableFuture<Optional<Lease>> acquire(ResourceKey resource, Duration ttl) {
        Objects.requireNonNull(resource, "resource");
        if (resource.name() == null || resource.name().isBlank()) {
            throw new IllegalArgumentException("resource name cannot be blank");
        }
        validateTtl(ttl);

        return PillarFutures.supplyGuarded(() -> delegate.acquire(resource, createOwnerToken(), ttl), ioPool, scheduler);
    }

    @Override
    public CompletableFuture<Boolean> renew(Lease lease, Duration ttl) {
        Objects.requireNonNull(lease, "lease");
        validateTtl(ttl);

        return PillarFutures.supplyGuarded(() -> delegate.renew(lease, ttl, createOwnerToken()), ioPool, scheduler);
    }

    @Override
    public CompletableFuture<Boolean> release(Lease lease) {
        Objects.requireNonNull(lease, "lease");

        return PillarFutures.supplyGuarded(() -> delegate.release(lease, createOwnerToken()), ioPool, scheduler);
    }
}
