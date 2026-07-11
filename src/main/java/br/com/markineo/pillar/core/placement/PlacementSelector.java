package br.com.markineo.pillar.core.placement;

import br.com.markineo.pillar.core.health.HealthSnapshot;
import br.com.markineo.pillar.core.identity.ServerIdentity;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

public final class PlacementSelector {

    private final ReservationRegistry reservations;

    public PlacementSelector(ReservationRegistry reservations) {
        this.reservations = reservations;
    }

    public Optional<ServerIdentity> select(List<ServerIdentity> eligibleNodes, Function<ServerIdentity, HealthSnapshot> healthLookup) {
        int size = eligibleNodes.size();
        if (size == 0) {
            return Optional.empty();
        }
        if (size == 1) {
            return Optional.of(eligibleNodes.getFirst());
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        int index1 = random.nextInt(size);
        int index2 = random.nextInt(size - 1);
        
        // Ensure index2 is different from index1 without a while loop
        if (index2 >= index1) {
            index2++;
        }

        ServerIdentity node1 = eligibleNodes.get(index1);
        ServerIdentity node2 = eligibleNodes.get(index2);

        HealthSnapshot health1 = healthLookup.apply(node1);
        HealthSnapshot health2 = healthLookup.apply(node2);

        int score1 = score(node1, health1);
        int score2 = score(node2, health2);

        return score1 <= score2 ? Optional.of(node1) : Optional.of(node2);
    }

    private int score(ServerIdentity node, HealthSnapshot snapshot) {
        int activeRes = reservations.activeReservations(node);
        if (snapshot == null) {
            // Degraded mode: fallback to least-connections.
            // Massive base score so dark nodes always lose to healthy ones,
            // but tie-break among dark nodes purely on active reservations.
            return 1_000_000_000 + activeRes;
        }
        return snapshot.players() + snapshot.pendingSignals() + activeRes;
    }
}
