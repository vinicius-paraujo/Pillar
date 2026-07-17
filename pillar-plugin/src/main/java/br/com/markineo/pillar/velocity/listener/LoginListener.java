package br.com.markineo.pillar.velocity.listener;

import br.com.markineo.pillar.config.Lang;
import br.com.markineo.pillar.config.PillarSettings;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.identity.ServerRole;
import br.com.markineo.pillar.core.placement.NoEligibleNodeException;
import br.com.markineo.pillar.core.placement.PlacementService;
import br.com.markineo.pillar.logger.PillarLogger;
import br.com.markineo.pillar.redis.presence.HealthRegistry;
import br.com.markineo.pillar.redis.presence.PresenceService;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.minimessage.MiniMessage;

import br.com.markineo.pillar.redis.lifecycle.RedisConnector;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public final class LoginListener {

    private final ProxyServer server;
    private final PlacementService placement;
    private final PresenceService presence;
    private final HealthRegistry healthRegistry;
    private final PillarSettings settings;
    private final Lang lang;
    private final PillarLogger logger;
    private final RedisConnector connector;

    private final AtomicInteger degradedRefusalCount = new AtomicInteger(0);
    private volatile boolean wasDegraded = false;

    public LoginListener(ProxyServer server, PlacementService placement, PresenceService presence, 
                         HealthRegistry healthRegistry, PillarSettings settings, Lang lang, PillarLogger logger, RedisConnector connector) {
        this.server = server;
        this.placement = placement;
        this.presence = presence;
        this.healthRegistry = healthRegistry;
        this.settings = settings;
        this.lang = lang;
        this.logger = logger;
        this.connector = connector;
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        ServerRole role = new ServerRole(settings.entryRole());
        String playerName = event.getPlayer().getUsername();
        
        boolean isDegraded = presence.isStale();
        if (wasDegraded && !isDegraded) {
            degradedRefusalCount.set(0);
            wasDegraded = false;
        } else if (isDegraded) {
            wasDegraded = true;
        }

        try {
            ServerIdentity chosen = placement.place(
                    role, 
                    presence.cachedFleet(), 
                    id -> healthRegistry.snapshot().get(id)
            );
            
            Optional<RegisteredServer> registeredServer = server.getServer(chosen.id().value());
            
            if (registeredServer.isPresent()) {
                event.setInitialServer(registeredServer.get());
                logger.debug("Placed player " + playerName + " on " + chosen.id().value() + " (role: " + role.value() + ")");
            } else {
                logger.warn("Placed player " + playerName + " on " + chosen.id().value() + ", but it is unroutable (no RegisteredServer). Refusing connection.");
                String message = lang.get("routing.unroutable");
                event.getPlayer().disconnect(MiniMessage.miniMessage().deserialize(message));
            }
        } catch (NoEligibleNodeException e) {
            if (isDegraded) {
                int count = degradedRefusalCount.incrementAndGet();
                if (count == 1) {
                    logger.warn("Network degraded and staleness window elapsed. Refusing login for " + playerName + " and suppressing further refusal logs until recovery.");
                }
            } else {
                logger.warn("Failed to place player " + playerName + ": no eligible node for role " + role.value() + ". Refusing connection.");
            }
            String message = lang.get("routing.no-eligible-node");
            event.getPlayer().disconnect(MiniMessage.miniMessage().deserialize(message));
        }
    }
}
