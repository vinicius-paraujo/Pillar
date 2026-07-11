package br.com.markineo.pillar.core.placement;

import java.util.UUID;

public record RoutePlayerRequest(UUID playerId, String targetServerId, String targetRole) {
}
