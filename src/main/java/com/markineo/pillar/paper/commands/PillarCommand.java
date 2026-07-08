package com.markineo.pillar.paper.commands;

import com.markineo.pillar.config.Lang;
import com.markineo.pillar.core.fleet.FleetSnapshot;
import com.markineo.pillar.core.identity.ServerId;
import com.markineo.pillar.core.identity.ServerIdentity;
import com.markineo.pillar.logger.PillarLogger;
import com.markineo.pillar.redis.InboxDiagnostics;
import com.markineo.pillar.redis.RedisConnector;
import com.markineo.pillar.redis.PresenceService;
import com.markineo.pillar.redis.RequestSender;
import com.markineo.pillar.core.task.Envelope;
import com.markineo.pillar.core.task.PillarMessageTypes;
import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public final class PillarCommand implements CommandExecutor {

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
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String subcommand = args.length == 0 ? "" : args[0].toLowerCase();
        switch (subcommand) {
            case "fleet" -> showFleet(sender);
            case "status" -> showStatus(sender);
            case "ping" -> pingServer(sender, args);
            default -> sender.sendMessage(render("command.usage"));
        }
        return true;
    }

    private void showFleet(CommandSender sender) {
        FleetSnapshot fleet = presence.fleet();
        sender.sendMessage(render("fleet.header", Placeholder.unparsed("count", Integer.toString(fleet.size()))));
        if (fleet.size() == 0) {
            sender.sendMessage(render("fleet.empty"));
            return;
        }
        for (ServerIdentity node : fleet.members()) {
            sender.sendMessage(render("fleet.entry",
                    Placeholder.unparsed("name", node.id().value()),
                    Placeholder.unparsed("role", node.role().value()),
                    Placeholder.unparsed("status", ONLINE)));
        }
    }

    private void showStatus(CommandSender sender) {
        sender.sendMessage(render("status.connection",
                Placeholder.unparsed("state", redis.state().name())));
        sender.sendMessage(render("status.pending",
                Placeholder.unparsed("count", Long.toString(diagnostics.pendingEntries(self)))));

        sender.sendMessage(render("status.log_header"));
        var history = logger.history();
        if (history.isEmpty()) {
            sender.sendMessage(render("status.log_empty"));
            return;
        }
        history.stream().skip(Math.max(0, history.size() - RECENT_LOG_LIMIT)).forEach(entry ->
                sender.sendMessage(render("status.log_entry",
                        Placeholder.unparsed("level", entry.level().name()),
                        Placeholder.unparsed("message", entry.message()))));
    }

    private void pingServer(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(render("command.usage"));
            return;
        }
        
        ServerId target = new ServerId(args[1]);
        if (!presence.fleet().contains(target)) {
            sender.sendMessage(render("ping.unknown_server", Placeholder.unparsed("server", target.value())));
            return;
        }

        Envelope ping = Envelope.request(PillarMessageTypes.PING, self, "{}");
        long start = System.currentTimeMillis();

        requestSender.send(target, ping, Duration.ofSeconds(5)).whenComplete((pong, error) -> {
            if (error != null) {
                sender.sendMessage(render("ping.timeout", Placeholder.unparsed("server", target.value())));
            } else {
                long latency = System.currentTimeMillis() - start;
                sender.sendMessage(render("ping.success",
                        Placeholder.unparsed("server", target.value()),
                        Placeholder.unparsed("latency", String.valueOf(latency))));
            }
        });
    }

    private Component render(String key, TagResolver... placeholders) {
        return MINI.deserialize(lang.get(key), placeholders);
    }
}
