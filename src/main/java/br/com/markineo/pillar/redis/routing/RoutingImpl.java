package br.com.markineo.pillar.redis.routing;

import br.com.markineo.pillar.api.Routing;
import br.com.markineo.pillar.concurrent.PillarFutures;
import br.com.markineo.pillar.concurrent.PlatformScheduler;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.placement.RouteOutcome;
import br.com.markineo.pillar.core.placement.RoutePlayerRequest;
import br.com.markineo.pillar.core.placement.RoutePlayerResponse;
import br.com.markineo.pillar.core.task.Envelope;
import br.com.markineo.pillar.core.task.EnvelopeCodec;
import br.com.markineo.pillar.core.task.MessageType;
import br.com.markineo.pillar.core.task.PillarMessageTypes;
import br.com.markineo.pillar.redis.presence.FleetView;
import br.com.markineo.pillar.redis.transport.RequestSender;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class RoutingImpl implements Routing {

    private final RequestSender requestSender;
    private final FleetView fleetView;
    private final EnvelopeCodec codec;
    private final PlatformScheduler scheduler;
    private final ExecutorService ioPool;
    private final ServerId selfId;
    private final String proxyRole;

    public RoutingImpl(
            RequestSender requestSender,
            FleetView fleetView,
            EnvelopeCodec codec,
            PlatformScheduler scheduler,
            ExecutorService ioPool,
            ServerId selfId,
            String proxyRole) {
        this.requestSender = requestSender;
        this.fleetView = fleetView;
        this.codec = codec;
        this.scheduler = scheduler;
        this.ioPool = ioPool;
        this.selfId = selfId;
        this.proxyRole = proxyRole;
    }

    private Optional<ServerId> findProxy() {
        return fleetView.snapshot()
                .flatMap(snapshot -> snapshot.members().stream()
                        .filter(identity -> identity.role().value().equals(proxyRole))
                        .map(ServerIdentity::id)
                        .findFirst());
    }

    private CompletableFuture<RouteOutcome> executeRequest(RoutePlayerRequest request) {
        CompletableFuture<RouteOutcome> guardedResult = PillarFutures.create(scheduler);

        CompletableFuture.runAsync(() -> {
            Optional<ServerId> proxyOpt = findProxy();
            if (proxyOpt.isEmpty()) {
                guardedResult.complete(RouteOutcome.PROXY_UNREACHABLE);
                return;
            }
            ServerId proxyId = proxyOpt.get();

            try {
                String json = codec.encodePayload(request);
                Envelope requestEnv = Envelope.request(PillarMessageTypes.ROUTE_PLAYER, selfId, json);
                
                requestSender.send(proxyId, requestEnv).whenComplete((responseEnv, ex) -> {
                    if (ex != null) {
                        guardedResult.complete(RouteOutcome.PROXY_UNREACHABLE);
                    } else {
                        try {
                            RoutePlayerResponse response = codec.decodePayload(responseEnv, RoutePlayerResponse.class);
                            guardedResult.complete(response.outcome());
                        } catch (Exception e) {
                            guardedResult.completeExceptionally(e);
                        }
                    }
                });
            } catch (Exception e) {
                guardedResult.completeExceptionally(e);
            }
        }, ioPool);

        return guardedResult;
    }

    @Override
    public CompletableFuture<RouteOutcome> moveToServer(UUID playerId, String targetServerId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(targetServerId, "targetServerId");
        if (targetServerId.isBlank()) {
            throw new IllegalArgumentException("targetServerId cannot be blank");
        }
        return executeRequest(new RoutePlayerRequest(playerId, targetServerId, null));
    }

    @Override
    public CompletableFuture<RouteOutcome> moveToRole(UUID playerId, String targetRole) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(targetRole, "targetRole");
        if (targetRole.isBlank()) {
            throw new IllegalArgumentException("targetRole cannot be blank");
        }
        return executeRequest(new RoutePlayerRequest(playerId, null, targetRole));
    }
}
