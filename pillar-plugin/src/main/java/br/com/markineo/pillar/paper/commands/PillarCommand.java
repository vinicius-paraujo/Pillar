package br.com.markineo.pillar.paper.commands;

import br.com.markineo.pillar.concurrent.PlatformScheduler;
import br.com.markineo.pillar.config.Configurations;
import br.com.markineo.pillar.config.Lang;
import br.com.markineo.pillar.core.fleet.FleetSnapshot;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.identity.ServerRole;
import br.com.markineo.pillar.error.TimeoutPillarException;
import br.com.markineo.pillar.error.PublishFailedException;
import br.com.markineo.pillar.logger.PillarLogger;
import br.com.markineo.pillar.redis.transport.InboxDiagnostics;
import br.com.markineo.pillar.redis.lifecycle.RedisConnector;
import br.com.markineo.pillar.redis.presence.PresenceService;
import br.com.markineo.pillar.redis.transport.RequestSender;
import br.com.markineo.pillar.core.task.Envelope;
import br.com.markineo.pillar.core.task.PillarMessageTypes;
import br.com.markineo.pillar.redis.transport.StreamConsumer;
import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class PillarCommand implements TabExecutor {

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
    private final StreamConsumer consumer;

    public PillarCommand(Lang lang, Configurations configurations, PresenceService presence, RedisConnector redis,
                         InboxDiagnostics diagnostics, RequestSender requestSender,
                         PlatformScheduler scheduler, ServerId self, PillarLogger logger,
                         StreamConsumer consumer) {
        this.lang = lang;
        this.configurations = configurations;
        this.presence = presence;
        this.redis = redis;
        this.diagnostics = diagnostics;
        this.requestSender = requestSender;
        this.scheduler = scheduler;
        this.self = self;
        this.logger = logger;
        this.consumer = consumer;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String subcommand = args.length == 0 ? "" : args[0].toLowerCase();
        switch (subcommand) {
            case "fleet" -> showFleet(sender);
            case "status" -> showStatus(sender);
            case "doctor" -> showDoctor(sender);
            case "ping" -> pingServer(sender, args);
            case "reload" -> reload(sender, args);
            default -> sender.sendMessage(render("command.usage"));
        }
        return true;
    }

    private void showFleet(CommandSender sender) {
        FleetSnapshot fleet = presence.cachedFleet();
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
        if (target.equals(self)) {
            sender.sendMessage(render("ping.self"));
            return;
        }

        if (!presence.cachedFleet().contains(target)) {
            sender.sendMessage(render("ping.unknown_server", Placeholder.unparsed("server", target.value())));
            return;
        }

        Envelope ping = Envelope.request(PillarMessageTypes.PING, self, "{}");
        long start = System.currentTimeMillis();

        requestSender.send(target, ping, Duration.ofSeconds(5)).whenComplete((pong, error) -> {
            scheduler.runSync(() -> {
                if (error != null) {
                    Throwable root = error instanceof java.util.concurrent.CompletionException ? error.getCause() : error;
                    if (root instanceof TimeoutPillarException) {
                        sender.sendMessage(render("ping.timeout", Placeholder.unparsed("server", target.value())));
                    } else if (root instanceof PublishFailedException) {
                        sender.sendMessage(render("ping.publish_failed", Placeholder.unparsed("server", target.value())));
                    } else {
                        sender.sendMessage(render("ping.failed", 
                                Placeholder.unparsed("server", target.value()),
                                Placeholder.unparsed("message", error.getMessage() != null ? error.getMessage() : "unknown error")));
                    }
                } else {
                    long latency = System.currentTimeMillis() - start;
                    sender.sendMessage(render("ping.success",
                            Placeholder.unparsed("server", target.value()),
                            Placeholder.unparsed("latency", String.valueOf(latency))));
                }
            });
        });
    }

    private void showDoctor(CommandSender sender) {
        sender.sendMessage(render("doctor.header"));

        // Connection / State
        sender.sendMessage(render("doctor.connection", 
                Placeholder.unparsed("state", redis.state().name()),
                Placeholder.unparsed("duration", formatDuration(redis.stateDuration()))));

        // Transport Anomalies (Paper only shows its own transport health)
        sender.sendMessage(render("doctor.transport_paper", 
                Placeholder.unparsed("pending", Long.toString(diagnostics.pendingEntries(self))),
                Placeholder.unparsed("dead_letters", Long.toString(consumer.deadLetterCount())),
                Placeholder.unparsed("dispatch_queue", Integer.toString(consumer.pendingSignals()))));
    }

    private String formatDuration(Duration d) {
        long s = d.getSeconds();
        return String.format("%02d:%02d", (s % 3600) / 60, s % 60);
    }

    private record ReloadAck(boolean ok, String reason) {}

    private void reload(CommandSender sender, String[] args) {
        if (args.length < 2) {
            configurations.reloadAll();
            sender.sendMessage(render("command.reloaded"));
            return;
        }
        
        ServerRole targetRole = new ServerRole(args[1]);
        List<ServerIdentity> targets = presence.cachedFleet().withRole(targetRole);

        if (targets.isEmpty()) {
            sender.sendMessage(render("reload.no_targets", Placeholder.unparsed("role", targetRole.value())));
            return;
        }

        sender.sendMessage(render("reload.dispatching", Placeholder.unparsed("count", String.valueOf(targets.size()))));

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        com.google.gson.Gson localGson = new com.google.gson.Gson();

        CompletableFuture<?>[] futures = targets.stream()
            .map(target -> {
                Envelope req = Envelope.request(PillarMessageTypes.RELOAD_CONFIG, self, "{}");
                return requestSender.send(target.id(), req, Duration.ofSeconds(5)).handle((reply, error) -> {
                    scheduler.runSync(() -> {
                        if (error == null) {
                            ReloadAck ack = localGson.fromJson(reply.payload(), ReloadAck.class);
                            if (ack != null && !ack.ok()) {
                                failures.incrementAndGet();
                                sender.sendMessage(render("reload.failed", 
                                        Placeholder.unparsed("server", target.id().value()),
                                        Placeholder.unparsed("message", ack.reason() != null ? ack.reason() : "Unknown error")));
                            } else {
                                successes.incrementAndGet();
                                sender.sendMessage(render("reload.success", Placeholder.unparsed("server", target.id().value())));
                            }
                        } else {
                            failures.incrementAndGet();
                            Throwable root = error instanceof CompletionException ? error.getCause() : error;
                            String msg = root.getMessage();
                            if (root instanceof TimeoutPillarException) {
                                msg = "timeout";
                            } else if (msg == null) {
                                msg = root.getClass().getSimpleName();
                            }
                            sender.sendMessage(render("reload.failed", 
                                    Placeholder.unparsed("server", target.id().value()),
                                    Placeholder.unparsed("message", msg)));
                        }
                    });
                    return null;
                });
            }).toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).thenRun(() -> {
            scheduler.runSync(() -> {
                sender.sendMessage(render("reload.summary", 
                        Placeholder.unparsed("success", String.valueOf(successes.get())),
                        Placeholder.unparsed("failures", String.valueOf(failures.get()))));
            });
        });
    }

    private Component render(String key, TagResolver... placeholders) {
        return MINI.deserialize(lang.get(key), placeholders);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return Stream.of("fleet", "status", "doctor", "ping", "reload")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        } else if (args.length == 2 && args[0].equalsIgnoreCase("ping")) {
            return presence.cachedFleet().members().stream()
                    .map(node -> node.id().value())
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        } else if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            return presence.cachedFleet().members().stream()
                    .map(node -> node.role().value())
                    .distinct()
                    .sorted()
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
