package br.com.markineo.pillar.api;

import br.com.markineo.pillar.api.routing.RouteOutcome;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Cross-server player routing, actuated through the proxy. Call it from a backend
 * server: {@link Pillar#routing()} throws on a proxy, where the platform's own API
 * already moves players.
 *
 * <p>Requires exactly one proxy in the fleet. More than one is unsupported: Pillar
 * does not detect it, and every outcome below becomes unreliable.
 *
 * <p>The proxy is resolved from this node's cached fleet, so a fleet read that has not
 * succeeded within the staleness window resolves to {@link
 * RouteOutcome#ACTUATION_FAILED} rather than routing on a guess.
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
public interface Routing {

    /**
     * Moves {@code playerId} to {@code targetServerId} directly.
     *
     * <p>Pillar rejects a null or blank argument before starting any asynchronous
     * work, so that failure surfaces synchronously rather than through the returned
     * future. This is a null/blank check only; whether the target actually exists in
     * the fleet resolves later, through the returned future's {@link RouteOutcome}.
     *
     * @param playerId the player to move
     * @param targetServerId the destination server ID
     * @return a future completed with the outcome of the move. Branch on {@link
     *     RouteOutcome#isSuccess()} and {@link RouteOutcome#isTransient()} rather than
     *     on the constants: this type gains outcomes over time, and asking what one
     *     means keeps working against a newer Pillar without recompiling
     * @throws NullPointerException if {@code playerId} or {@code targetServerId} is
     *     null
     * @throws IllegalArgumentException if {@code targetServerId} is blank
     */
    CompletableFuture<RouteOutcome> moveToServer(UUID playerId, String targetServerId);

    /**
     * Moves {@code playerId} to whichever server is currently the best pick for
     * {@code targetRole}, the same selection the proxy uses for login placement.
     *
     * <p>Pillar rejects a null or blank argument before starting any asynchronous
     * work, so that failure surfaces synchronously rather than through the returned
     * future. This is a null/blank check only; the role is not checked against any
     * fixed set, since roles have none.
     *
     * @param playerId the player to move
     * @param targetRole the role to select a destination server from
     * @return a future completed with the outcome of the move. Branch on {@link
     *     RouteOutcome#isSuccess()} and {@link RouteOutcome#isTransient()} rather than
     *     on the constants: this type gains outcomes over time, and asking what one
     *     means keeps working against a newer Pillar without recompiling
     * @throws NullPointerException if {@code playerId} or {@code targetRole} is null
     * @throws IllegalArgumentException if {@code targetRole} is blank
     */
    CompletableFuture<RouteOutcome> moveToRole(UUID playerId, String targetRole);
}
