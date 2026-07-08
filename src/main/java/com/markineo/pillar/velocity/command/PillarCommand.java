package com.markineo.pillar.velocity.command;

import com.markineo.pillar.config.Lang;
import com.markineo.pillar.core.fleet.FleetSnapshot;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.identity.ServerIdentity;
import com.markineo.pillar.logger.PillarLogger;
import com.markineo.pillar.redis.InboxDiagnostics;
import com.markineo.pillar.redis.PresenceService;
import com.markineo.pillar.redis.RedisConnector;
import com.markineo.pillar.redis.RequestSender;
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
    private final PresenceService presence;
    private final RedisConnector redis;
    private final InboxDiagnostics diagnostics;
    private final RequestSender requestSender;
    private final ServerId self;
    private final PillarLogger logger;

    public PillarCommand(Lang lang, PresenceService presence, RedisConnector redis,
                         InboxDiagnostics diagnostics, RequestSender requestSender, ServerId self, PillarLogger logger) {
        this.lang = lang;
        this.presence = presence;
        this.redis = redis;
        this.diagnostics = diagnostics;
        this.requestSender = requestSender;
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
            default -> source.sendMessage(render("command.usage"));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("pillar.admin");
    }

    private void showFleet(CommandSource source) {
        FleetSnapshot fleet = presence.fleet();
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
        if (!presence.fleet().contains(target)) {
            source.sendMessage(render("ping.unknown_server", Placeholder.unparsed("server", target.value())));
            return;
        }

        Envelope ping = Envelope.request(PillarMessageTypes.PING, self, "{}");
        long start = System.currentTimeMillis();

        requestSender.send(target, ping, Duration.ofSeconds(5)).whenComplete((pong, error) -> {
            if (error != null) {
                source.sendMessage(render("ping.timeout", Placeholder.unparsed("server", target.value())));
            } else {
                long latency = System.currentTimeMillis() - start;
                source.sendMessage(render("ping.success",
                        Placeholder.unparsed("server", target.value()),
                        Placeholder.unparsed("latency", String.valueOf(latency))));
            }
        });
    }

    private Component render(String key, TagResolver... placeholders) {
        return MINI.deserialize(lang.get(key), placeholders);
    }
}
