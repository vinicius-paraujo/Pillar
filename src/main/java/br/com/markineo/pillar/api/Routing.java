package br.com.markineo.pillar.api;

import br.com.markineo.pillar.core.placement.RouteOutcome;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface Routing {
    CompletableFuture<RouteOutcome> moveToServer(UUID playerId, String targetServerId);
    CompletableFuture<RouteOutcome> moveToRole(UUID playerId, String targetRole);
}
