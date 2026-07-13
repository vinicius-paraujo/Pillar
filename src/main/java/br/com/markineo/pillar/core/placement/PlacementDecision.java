package br.com.markineo.pillar.core.placement;

import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerRole;

import java.time.Instant;
import java.util.Optional;

public sealed interface PlacementDecision permits PlacementDecision.Success, PlacementDecision.Refused {
    Instant when();
    ServerRole role();

    static PlacementDecision success(Instant when, ServerRole role, ServerId chosenNode) {
        return new Success(when, role, chosenNode);
    }

    static PlacementDecision refused(Instant when, ServerRole role, String refusalReason) {
        return new Refused(when, role, refusalReason);
    }

    record Success(Instant when, ServerRole role, ServerId chosenNode) implements PlacementDecision {}
    record Refused(Instant when, ServerRole role, String refusalReason) implements PlacementDecision {}
}
