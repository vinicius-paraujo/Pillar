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
import br.com.markineo.pillar.logger.PillarLogger;
import br.com.markineo.pillar.redis.presence.PresenceService;
import br.com.markineo.pillar.redis.transport.RequestSender;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class RoutingImpl implements Routing {

    private final RequestSender requestSender;
    private final PresenceService presence;
    private final EnvelopeCodec codec;
    private final PlatformScheduler scheduler;
    private final ExecutorService ioPool;
    private final ServerId selfId;
    private final String proxyRole;
    private final PillarLogger logger;

    public RoutingImpl(
            RequestSender requestSender,
            PresenceService presence,
            EnvelopeCodec codec,
            PlatformScheduler scheduler,
            ExecutorService ioPool,
            ServerId selfId,
            String proxyRole,
            PillarLogger logger) {
        this.requestSender = requestSender;
        this.presence = presence;
        this.codec = codec;
        this.scheduler = scheduler;
        this.ioPool = ioPool;
        this.selfId = selfId;
        this.proxyRole = proxyRole;
        this.logger = logger;
    }

    // A proxy on a newer Pillar can report an outcome this version never defined. Degrading
    // to ACTUATION_FAILED keeps isSuccess()/isTransient() answering sanely instead of failing
    // the future over a name.
    private RouteOutcome resolve(String outcomeName, ServerId proxyId) {
        Optional<RouteOutcome> known = RouteOutcome.byName(outcomeName);
        if (known.isEmpty()) {
            logger.warn("Proxy " + proxyId.value() + " reported route outcome '" + outcomeName
                    + "', which this version of Pillar does not define; treating it as "
                    + RouteOutcome.ACTUATION_FAILED.name() + ".");
            return RouteOutcome.ACTUATION_FAILED;
        }
        return known.get();
    }

    private Optional<ServerId> findProxy() {
        return presence.cachedFleet().members().stream()
                .filter(identity -> identity.role().value().equals(proxyRole))
                .map(ServerIdentity::id)
                .findFirst();
    }

    private CompletableFuture<RouteOutcome> executeRequest(RoutePlayerRequest request) {
        CompletableFuture<RouteOutcome> guardedResult = PillarFutures.create(scheduler);

        CompletableFuture.runAsync(() -> {
            // Past the window the cached fleet is a guess, not a reading. Checking it here
            // rather than trusting emptiness matters because the tick that blanks the cache
            // runs on its own interval, so a retained proxy can still be named for a beat
            // after the window closes.
            if (presence.isStale()) {
                logger.warn("Cannot route player " + request.playerId()
                        + ": no fleet read has succeeded within the staleness window.");
                guardedResult.complete(RouteOutcome.ACTUATION_FAILED);
                return;
            }

            Optional<ServerId> proxyOpt = findProxy();
            if (proxyOpt.isEmpty()) {
                logger.warn("Cannot route player " + request.playerId() + ": no node with role '"
                        + proxyRole + "' in the cached fleet.");
                guardedResult.complete(RouteOutcome.ACTUATION_FAILED);
                return;
            }
            ServerId proxyId = proxyOpt.get();

            try {
                String json = codec.encodePayload(request);
                Envelope requestEnv = Envelope.request(PillarMessageTypes.ROUTE_PLAYER, selfId, json);

                requestSender.send(proxyId, requestEnv).whenComplete((responseEnv, ex) -> {
                    if (ex != null) {
                        // ACTUATION_FAILED erases the cause by design, so this log is the only
                        // place an operator can tell a Redis outage from a slow proxy.
                        logger.error("Routing player " + request.playerId() + " through proxy "
                                + proxyId.value() + " failed before an outcome came back.", ex);
                        guardedResult.complete(RouteOutcome.ACTUATION_FAILED);
                    } else {
                        try {
                            RoutePlayerResponse response = codec.decodePayload(responseEnv, RoutePlayerResponse.class);
                            guardedResult.complete(resolve(response.outcome(), proxyId));
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
