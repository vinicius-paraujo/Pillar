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
            logger.error("Failed to parse RoutePlayerRequest from envelope " + envelope.correlationId().map(c -> c.value()).orElse("none"), e);
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
                    reply(envelope, RouteOutcome.UNKNOWN_TARGET);
                    return;
                }
            } catch (NoEligibleNodeException e) {
                reply(envelope, RouteOutcome.NO_ELIGIBLE_NODE);
                return;
            }
        } else {
            logger.warn("RoutePlayerRequest must specify either targetServerId or targetRole.");
            reply(envelope, RouteOutcome.UNKNOWN_TARGET);
            return;
        }

        player.createConnectionRequest(targetServer).connect().whenComplete((result, ex) -> {
            if (ex != null) {
                logger.error("Connection request failed for player " + player.getUsername(), ex);
                reply(envelope, RouteOutcome.CONNECTION_FAILED);
                return;
            }
            if (result.isSuccessful()) {
                reply(envelope, RouteOutcome.SUCCESS);
            } else {
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
