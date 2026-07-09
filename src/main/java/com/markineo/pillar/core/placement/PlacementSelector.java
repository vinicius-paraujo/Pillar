package com.markineo.pillar.core.placement;

import com.markineo.pillar.core.health.HealthSnapshot;
import com.markineo.pillar.core.identity.ServerIdentity;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

public final class PlacementSelector {

    public Optional<ServerIdentity> select(List<ServerIdentity> eligibleNodes, Function<ServerIdentity, HealthSnapshot> healthLookup) {
        int size = eligibleNodes.size();
        if (size == 0) {
            return Optional.empty();
        }
        if (size == 1) {
            return Optional.of(eligibleNodes.get(0));
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

        int score1 = score(health1);
        int score2 = score(health2);

        return score1 <= score2 ? Optional.of(node1) : Optional.of(node2);
    }

    private int score(HealthSnapshot snapshot) {
        if (snapshot == null) {
            return Integer.MAX_VALUE;
        }
        return snapshot.players() + snapshot.pendingSignals();
    }
}
