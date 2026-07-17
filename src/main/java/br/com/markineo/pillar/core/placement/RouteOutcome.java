package br.com.markineo.pillar.core.placement;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Result of a {@link br.com.markineo.pillar.api.Routing} move.
 *
 * <p>Branch on {@link #isSuccess()} and {@link #isTransient()} rather than on identity.
 * This type gains constants over time, and a consumer that asks what an outcome means
 * keeps behaving correctly against a newer Pillar without recompiling; one that
 * enumerates the constants it knows does not.
 */
public final class RouteOutcome {

    private static final Map<String, RouteOutcome> BY_NAME = new LinkedHashMap<>();

    /**
     * The proxy delivered the player to the target server.
     */
    public static final RouteOutcome SUCCESS = define("SUCCESS", true, false);

    /**
     * No server matching the requested role currently qualifies: all are over a hard
     * cap, or none is present in the fleet. Load changes, so this can clear on its own.
     */
    public static final RouteOutcome NO_ELIGIBLE_NODE = define("NO_ELIGIBLE_NODE", false, true);

    /**
     * The proxy does not recognize the resolved destination server. For {@code
     * moveToServer}, the given server ID itself is unknown to the proxy. For {@code
     * moveToRole}, placement selected a server for the role, but that server is not
     * registered on the proxy (a stale fleet view). This is not about the role string
     * being invalid; roles have no fixed set, so no role is ever "unknown" on its own.
     * The same call will not start working, so this does not clear on its own.
     */
    public static final RouteOutcome UNKNOWN_TARGET = define("UNKNOWN_TARGET", false, false);

    /**
     * The player is not currently connected to the network. Repeating the move will not
     * bring them back.
     */
    public static final RouteOutcome PLAYER_OFFLINE = define("PLAYER_OFFLINE", false, false);

    /**
     * The target server refused or dropped the connection attempt. Pillar evicts that
     * node from its cached fleet, so a role-targeted retry lands elsewhere.
     */
    public static final RouteOutcome CONNECTION_FAILED = define("CONNECTION_FAILED", false, true);

    /**
     * Pillar never got the move actuated, and the reason is infrastructure rather than
     * the move itself: no proxy in the cached fleet, no successful fleet read inside
     * the staleness window, no answer from the proxy before the timeout, a transport
     * failure publishing the request, or a local node shutting down. The specific cause
     * is logged on the node that made the call, not carried here. It also stands in for
     * any outcome a newer Pillar reports that this version does not know.
     */
    public static final RouteOutcome ACTUATION_FAILED = define("ACTUATION_FAILED", false, true);

    private final String name;
    private final boolean success;
    private final boolean transientCondition;

    private RouteOutcome(String name, boolean success, boolean transientCondition) {
        this.name = name;
        this.success = success;
        this.transientCondition = transientCondition;
    }

    private static RouteOutcome define(String name, boolean success, boolean transientCondition) {
        RouteOutcome outcome = new RouteOutcome(name, success, transientCondition);
        BY_NAME.put(name, outcome);
        return outcome;
    }

    /**
     * Resolves an outcome from the name it travels under.
     *
     * @param name the outcome name
     * @return the matching outcome, or empty if this version does not define it
     */
    public static Optional<RouteOutcome> byName(String name) {
        return Optional.ofNullable(BY_NAME.get(name));
    }

    /**
     * Every outcome this version defines, in declaration order.
     *
     * @return the defined outcomes
     */
    public static Collection<RouteOutcome> values() {
        return Collections.unmodifiableCollection(BY_NAME.values());
    }

    /**
     * The name this outcome travels under.
     *
     * @return the outcome name
     */
    public String name() {
        return name;
    }

    /**
     * Whether the player reached the target server.
     *
     * @return {@code true} only for {@link #SUCCESS}
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Whether the condition behind this outcome can clear without anyone changing
     * configuration or code. Pillar states the fact and imposes no retry policy: the
     * backoff, the ceiling, and whether to retry at all are the caller's.
     *
     * @return {@code true} if the same call could succeed later
     */
    public boolean isTransient() {
        return transientCondition;
    }

    @Override
    public String toString() {
        return name;
    }
}
