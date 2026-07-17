package br.com.markineo.pillar.api;

/**
 * Entry point to the Pillar control plane. Obtain an instance through
 * {@link PillarProvider#get()} once Pillar has finished loading, then reach the
 * submodules from here.
 *
 * <p>Every submodule is safe to call from any thread. None of them touch the game
 * thread on your behalf; if a callback needs to run there, hop to it yourself.
 */
public interface Pillar {

    /**
     * Cross-server messaging: one-way sends, request/response, and fleet-wide
     * broadcast.
     */
    Messaging messaging();

    /**
     * Distributed leases for mutual exclusion across the fleet.
     */
    Leases leases();

    /**
     * Cross-server player routing, available on backend servers only.
     *
     * @throws UnsupportedOperationException if called on a proxy, which moves its own
     *     players through the platform's API and has no use for the actuation path
     */
    Routing routing();
}
