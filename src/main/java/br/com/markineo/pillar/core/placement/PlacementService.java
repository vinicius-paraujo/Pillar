package br.com.markineo.pillar.core.placement;

import br.com.markineo.pillar.core.fleet.FleetSnapshot;
import br.com.markineo.pillar.core.health.HealthSnapshot;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.identity.ServerRole;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public final class PlacementService {

    private final EligibilityFilter filter;
    private final PlacementSelector selector;
    private final ReservationRegistry reservations;

    public PlacementService(EligibilityFilter filter, PlacementSelector selector, ReservationRegistry reservations) {
        this.filter = filter;
        this.selector = selector;
        this.reservations = reservations;
    }

    public ServerIdentity place(ServerRole role, FleetSnapshot fleet, Function<ServerIdentity, HealthSnapshot> healthLookup) throws NoEligibleNodeException {
        List<ServerIdentity> candidates = fleet.withRole(role);
        
        List<ServerIdentity> eligible = candidates.stream()
                .filter(node -> filter.isEligible(healthLookup.apply(node)))
                .toList();

        Optional<ServerIdentity> selected = selector.select(eligible, healthLookup);

        return selected.map(node -> {
            reservations.reserve(node);
            return node;
        }).orElseThrow(() -> new NoEligibleNodeException(role));
    }
}
