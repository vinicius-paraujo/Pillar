package com.markineo.pillar.velocity.command;

import com.markineo.pillar.concurrent.PlatformScheduler;
import com.markineo.pillar.config.Configurations;
import com.markineo.pillar.config.Lang;
import com.markineo.pillar.core.fleet.FleetSnapshot;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.identity.ServerIdentity;
import com.markineo.pillar.error.TimeoutPillarException;
import com.markineo.pillar.logger.PillarLogger;
import com.markineo.pillar.redis.transport.InboxDiagnostics;
import com.markineo.pillar.redis.presence.PresenceService;
import com.markineo.pillar.redis.lifecycle.RedisConnector;
import com.markineo.pillar.redis.transport.RequestSender;
import com.markineo.pillar.core.task.Envelope;
import com.markineo.pillar.core.task.PillarMessageTypes;
import java.time.Duration;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

public final class PillarCommand implements SimpleCommand {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int RECENT_LOG_LIMIT = 8;
    private static final String ONLINE = "online";

    private final Lang lang;
    private final Configurations configurations;
    private final PresenceService presence;
    private final RedisConnector redis;
    private final InboxDiagnostics diagnostics;
    private final RequestSender requestSender;
    private final PlatformScheduler scheduler;
    private final ServerId self;
    private final PillarLogger logger;

    public PillarCommand(Lang lang, Configurations configurations, PresenceService presence, RedisConnector redis,
                         InboxDiagnostics diagnostics, RequestSender requestSender,
                         PlatformScheduler scheduler, ServerId self, PillarLogger logger) {
        this.lang = lang;
        this.configurations = configurations;
        this.presence = presence;
        this.redis = redis;
        this.diagnostics = diagnostics;
        this.requestSender = requestSender;
        this.scheduler = scheduler;
        this.self = self;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        String subcommand = args.length == 0 ? "" : args[0].toLowerCase();
        switch (subcommand) {
            case "fleet" -> showFleet(source);
            case "status" -> showStatus(source);
            case "ping" -> pingServer(source, args);
            case "reload" -> reload(source);
            default -> source.sendMessage(render("command.usage"));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("pillar.admin");
    }

    private void showFleet(CommandSource source) {
        FleetSnapshot fleet = presence.cachedFleet();
        source.sendMessage(render("fleet.header", Placeholder.unparsed("count", Integer.toString(fleet.size()))));
        if (fleet.size() == 0) {
            source.sendMessage(render("fleet.empty"));
            return;
        }
        for (ServerIdentity node : fleet.members()) {
            source.sendMessage(render("fleet.entry",
                    Placeholder.unparsed("name", node.id().value()),
                    Placeholder.unparsed("role", node.role().value()),
                    Placeholder.unparsed("status", ONLINE)));
        }
    }

    private void showStatus(CommandSource source) {
        source.sendMessage(render("status.connection",
                Placeholder.unparsed("state", redis.state().name())));
        source.sendMessage(render("status.pending",
                Placeholder.unparsed("count", Long.toString(diagnostics.pendingEntries(self)))));

        source.sendMessage(render("status.log_header"));
        var history = logger.history();
        if (history.isEmpty()) {
            source.sendMessage(render("status.log_empty"));
            return;
        }
        history.stream().skip(Math.max(0, history.size() - RECENT_LOG_LIMIT)).forEach(entry ->
                source.sendMessage(render("status.log_entry",
                        Placeholder.unparsed("level", entry.level().name()),
                        Placeholder.unparsed("message", entry.message()))));
    }

    private void pingServer(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(render("command.usage"));
            return;
        }

        ServerId target = new ServerId(args[1]);
        if (target.equals(self)) {
            source.sendMessage(render("ping.self"));
            return;
        }

        if (!presence.cachedFleet().contains(target)) {
            source.sendMessage(render("ping.unknown_server", Placeholder.unparsed("server", target.value())));
            return;
        }

        Envelope ping = Envelope.request(PillarMessageTypes.PING, self, "{}");
        long start = System.currentTimeMillis();

        requestSender.send(target, ping, Duration.ofSeconds(5)).whenComplete((pong, error) -> {
            scheduler.runSync(() -> {
                if (error != null) {
                    Throwable root = error instanceof java.util.concurrent.CompletionException ? error.getCause() : error;
                    if (root instanceof TimeoutPillarException) {
                        source.sendMessage(render("ping.timeout", Placeholder.unparsed("server", target.value())));
                    } else {
                        source.sendMessage(render("ping.failed", 
                                Placeholder.unparsed("server", target.value()),
                                Placeholder.unparsed("message", error.getMessage() != null ? error.getMessage() : "unknown error")));
                    }
                } else {
                    long latency = System.currentTimeMillis() - start;
                    source.sendMessage(render("ping.success",
                            Placeholder.unparsed("server", target.value()),
                            Placeholder.unparsed("latency", String.valueOf(latency))));
                }
            });
        });
    }

    private void reload(CommandSource source) {
        // Refreshes message text and by-path config values; identity, Redis, and the active
        // locale are startup snapshots and only change on restart.
        configurations.reloadAll();
        source.sendMessage(render("command.reloaded"));
    }

    private Component render(String key, TagResolver... placeholders) {
        return MINI.deserialize(lang.get(key), placeholders);
    }

    @Override
    public java.util.List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0 || args.length == 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            return java.util.stream.Stream.of("fleet", "status", "ping", "reload")
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        } else if (args.length == 2 && args[0].equalsIgnoreCase("ping")) {
            return presence.cachedFleet().members().stream()
                    .map(node -> node.id().value())
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return java.util.List.of();
    }
}
