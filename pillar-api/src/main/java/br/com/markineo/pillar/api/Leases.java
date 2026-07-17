package br.com.markineo.pillar.api;

import br.com.markineo.pillar.api.lease.Lease;
import br.com.markineo.pillar.api.lease.ResourceKey;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Distributed mutual exclusion over a {@link ResourceKey}, backed by a TTL, not a
 * fencing token. If a node dies mid-lease, the resource frees itself once the TTL
 * expires; nothing stops a stale holder from still acting on the resource until it
 * notices the lease is gone. Do not use this alone to protect work where two holders
 * acting at once for a few seconds causes damage.
 *
 * <p>Every {@link CompletableFuture} returned by this interface completes on an
 * internal Pillar thread, never the platform's main thread. Which thread varies by
 * call and is not part of the contract; a callback chained with {@code
 * thenAccept}/{@code thenApply} runs on whatever thread that was, so touching Bukkit
 * or Velocity API from it is unsafe without hopping back to the main thread first.
 * Calling {@code join()} or {@code get()} on the returned future from the main thread
 * throws {@link IllegalStateException} instead of blocking; this applies anywhere on
 * the main thread, including plugin startup, not only mid-tick.
 */
public interface Leases {

    /**
     * Attempts to acquire exclusive ownership of {@code resource} for {@code ttl}.
     *
     * @param resource the resource to lock
     * @param ttl how long the hold lasts before it expires on its own
     * @return a future that completes with a {@link Lease} if acquired, or empty if
     *     another node currently holds it
     * @throws NullPointerException if {@code resource} or {@code ttl} is null
     * @throws IllegalArgumentException if {@code resource}'s name is blank, or if
     *     {@code ttl} is zero or negative
     */
    CompletableFuture<Optional<Lease>> acquire(ResourceKey resource, Duration ttl);

    /**
     * Extends {@code lease} by {@code ttl} from now, provided the caller still holds
     * it. A process restart changes the caller's owner identity, so a lease acquired
     * before the restart can no longer be renewed by the same logical process; treat
     * it as lost and re-acquire.
     *
     * @param lease the held lease to extend
     * @param ttl how long the hold lasts from now
     * @return {@code true} if renewed, {@code false} if the lease was lost (expired,
     *     taken over by another node, or held by a since-restarted instance of this
     *     node)
     * @throws NullPointerException if {@code lease} or {@code ttl} is null
     * @throws IllegalArgumentException if {@code ttl} is zero or negative
     */
    CompletableFuture<Boolean> renew(Lease lease, Duration ttl);

    /**
     * Releases {@code lease} early, before its TTL expires. Subject to the same
     * owner-identity restriction as {@link #renew}: a lease acquired before a process
     * restart cannot be released by the restarted process.
     *
     * @param lease the held lease to release
     * @return {@code true} if released, {@code false} if the caller no longer held it
     * @throws NullPointerException if {@code lease} is null
     */
    CompletableFuture<Boolean> release(Lease lease);
}
