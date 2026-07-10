package com.markineo.pillar.velocity.handler;

import com.google.gson.Gson;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.identity.ServerIdentity;
import com.markineo.pillar.core.identity.ServerRole;
import com.markineo.pillar.core.placement.NoEligibleNodeException;
import com.markineo.pillar.core.placement.PlacementService;
import com.markineo.pillar.core.placement.RouteOutcome;
import com.markineo.pillar.core.placement.RoutePlayerRequest;
import com.markineo.pillar.core.placement.RoutePlayerResponse;
import com.markineo.pillar.core.task.CorrelationId;
import com.markineo.pillar.core.task.Envelope;
import com.markineo.pillar.core.task.EnvelopeCodec;
import com.markineo.pillar.core.task.MessageHandler;
import com.markineo.pillar.core.task.MessageType;
import com.markineo.pillar.core.task.PillarMessageTypes;
import com.markineo.pillar.error.PillarException;
import com.markineo.pillar.logger.PillarLogger;
import com.markineo.pillar.redis.presence.HealthRegistry;
import com.markineo.pillar.redis.presence.PresenceService;
import com.markineo.pillar.redis.transport.StreamPublisher;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Optional;

public final class RoutePlayerHandler implements MessageHandler {

    private final ProxyServer server;
    private final PlacementService placement;
    private final PresenceService presence;
    private final HealthRegistry healthRegistry;
    private final StreamPublisher publisher;
    private final ServerId selfId;
    private final Gson gson;
    private final PillarLogger logger;

    public RoutePlayerHandler(ProxyServer server, PlacementService placement, PresenceService presence, 
                              HealthRegistry healthRegistry, StreamPublisher publisher, ServerId selfId, 
                              Gson gson, PillarLogger logger) {
        this.server = server;
        this.placement = placement;
        this.presence = presence;
        this.healthRegistry = healthRegistry;
        this.publisher = publisher;
        this.selfId = selfId;
        this.gson = gson;
        this.logger = logger;
    }

    @Override
    public MessageType type() {
        return PillarMessageTypes.ROUTE_PLAYER;
    }

    @Override
    public void handle(Envelope envelope, EnvelopeCodec codec) {
        RoutePlayerRequest request;
        try {
            request = gson.fromJson(envelope.payload(), RoutePlayerRequest.class);
        } catch (Exception e) {
            logger.error("Failed to parse RoutePlayerRequest from envelope " + envelope.correlationId().map(CorrelationId::value).orElse("none"), e);
            return;
        }

        Optional<Player> playerOpt = server.getPlayer(request.playerId());
        if (playerOpt.isEmpty()) {
            reply(envelope, RouteOutcome.PLAYER_OFFLINE);
            return;
        }
        Player player = playerOpt.get();

        RegisteredServer targetServer = null;

        if (request.targetServerId() != null) {
            Optional<RegisteredServer> srv = server.getServer(request.targetServerId());
            if (srv.isPresent()) {
                targetServer = srv.get();
            } else {
                logger.warn("Failed to route player " + player.getUsername() + " to server " + request.targetServerId() + ": server unknown to proxy.");
                reply(envelope, RouteOutcome.UNKNOWN_TARGET);
                return;
            }
        } else if (request.targetRole() != null) {
            try {
                ServerIdentity chosen = placement.place(
                        new ServerRole(request.targetRole()), 
                        presence.cachedFleet(), 
                        id -> healthRegistry.snapshot().get(id)
                );
                Optional<RegisteredServer> srv = server.getServer(chosen.id().value());
                if (srv.isPresent()) {
                    targetServer = srv.get();
                } else {
                    logger.warn("Failed to route player " + player.getUsername() + " to server " + chosen.id().value() + " (chosen for role " + request.targetRole() + "): server unknown to proxy.");
                    reply(envelope, RouteOutcome.UNKNOWN_TARGET);
                    return;
                }
            } catch (NoEligibleNodeException e) {
                logger.warn("Failed to place player " + player.getUsername() + " on role " + request.targetRole() + ": no eligible node.");
                reply(envelope, RouteOutcome.NO_ELIGIBLE_NODE);
                return;
            }
        } else {
            logger.warn("RoutePlayerRequest must specify either targetServerId or targetRole.");
            reply(envelope, RouteOutcome.UNKNOWN_TARGET);
            return;
        }

        // Note on failure window: The StreamConsumer XACKs the message as soon as this handle() method returns.
        // Because connect() is asynchronous, handle() returns immediately, meaning XACK happens before the reply is published.
        // If the proxy crashes in the millisecond window between XACK and this whenComplete callback, the caller will never receive
        // the response and will eventually time out. This is an acceptable MVP trade-off to avoid blocking the dispatch thread.
        final RegisteredServer finalTarget = targetServer;
        final ServerId finalTargetId = new ServerId(finalTarget.getServerInfo().getName());
        player.createConnectionRequest(finalTarget).connect().whenComplete((result, ex) -> {
            if (ex != null) {
                logger.error("Connection request failed for player " + player.getUsername(), ex);
                presence.evict(finalTargetId);
                reply(envelope, RouteOutcome.CONNECTION_FAILED);
                return;
            }
            if (result.isSuccessful()) {
                logger.debug("Routed player " + player.getUsername() + " to " + finalTarget.getServerInfo().getName() + ".");
                reply(envelope, RouteOutcome.SUCCESS);
            } else {
                presence.evict(finalTargetId);
                reply(envelope, RouteOutcome.CONNECTION_FAILED);
            }
        });
    }

    private void reply(Envelope requestEnvelope, RouteOutcome outcome) {
        if (requestEnvelope.correlationId().isEmpty()) {
            return; // No one is waiting for a response
        }
        
        try {
            String payload = gson.toJson(new RoutePlayerResponse(outcome));
            Envelope response = Envelope.response(requestEnvelope.type(), requestEnvelope.correlationId().get(), selfId, payload);
            publisher.publish(requestEnvelope.senderId(), response);
        } catch (PillarException e) {
            logger.error("Failed to publish route player response to " + requestEnvelope.senderId().value(), e);
        } catch (Exception e) {
            logger.error("Unexpected error publishing route player response", e);
        }
    }
}
