package br.com.markineo.pillar.velocity.command;

import br.com.markineo.pillar.concurrent.PlatformScheduler;
import br.com.markineo.pillar.config.Configurations;
import br.com.markineo.pillar.config.Lang;
import br.com.markineo.pillar.core.fleet.FleetSnapshot;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.identity.ServerIdentity;
import br.com.markineo.pillar.core.identity.ServerRole;
import br.com.markineo.pillar.error.TimeoutPillarException;
import br.com.markineo.pillar.logger.PillarLogger;
import br.com.markineo.pillar.redis.transport.InboxDiagnostics;
import br.com.markineo.pillar.redis.presence.PresenceService;
import br.com.markineo.pillar.redis.lifecycle.RedisConnector;
import br.com.markineo.pillar.redis.transport.RequestSender;
import br.com.markineo.pillar.core.task.Envelope;
import br.com.markineo.pillar.core.task.PillarMessageTypes;
import br.com.markineo.pillar.core.placement.DecisionBuffer;
import br.com.markineo.pillar.core.placement.PlacementDecision;
import br.com.markineo.pillar.redis.presence.HealthRegistry;
import br.com.markineo.pillar.redis.transport.InboxReaper;
import br.com.markineo.pillar.redis.transport.StreamConsumer;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

public final class PillarCommand implements SimpleCommand {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Gson GSON = new Gson();
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
    private final InboxReaper reaper;
    private final HealthRegistry healthRegistry;
    private final DecisionBuffer decisionBuffer;

    public PillarCommand(Lang lang, Configurations configurations, PresenceService presence, RedisConnector redis,
                         InboxDiagnostics diagnostics, RequestSender requestSender,
                         PlatformScheduler scheduler, ServerId self, PillarLogger logger,
                         StreamConsumer consumer,
                         InboxReaper reaper,
                         HealthRegistry healthRegistry,
                         DecisionBuffer decisionBuffer) {
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
        this.reaper = reaper;
        this.healthRegistry = healthRegistry;
        this.decisionBuffer = decisionBuffer;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        String subcommand = args.length == 0 ? "" : args[0].toLowerCase();
        switch (subcommand) {
            case "fleet" -> showFleet(source);
            case "status" -> showStatus(source);
            case "doctor" -> showDoctor(source);
            case "ping" -> pingServer(source, args);
            case "reload" -> reload(source, args);
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

        for (ServerIdentity member : fleet.members()) {
            source.sendMessage(render("fleet.entry",
                    Placeholder.unparsed("name", member.id().value()),
                    Placeholder.unparsed("role", member.role().value()),
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
                if (error == null) {
                    long latency = System.currentTimeMillis() - start;
                    source.sendMessage(render("ping.success",
                            Placeholder.unparsed("server", target.value()),
                            Placeholder.unparsed("latency", String.valueOf(latency))));
                    return;
                }

                Throwable root = error instanceof CompletionException ? error.getCause() : error;
                if (root instanceof TimeoutPillarException) {
                    source.sendMessage(render("ping.timeout", Placeholder.unparsed("server", target.value())));
                } else {
                    source.sendMessage(render("ping.failed",
                            Placeholder.unparsed("server", target.value()),
                            Placeholder.unparsed("message", error.getMessage() != null ? error.getMessage() : "unknown error")));
                }

            });
        });
    }

    private void showDoctor(CommandSource source) {
        source.sendMessage(render("doctor.header"));

        // Connection / State
        source.sendMessage(render("doctor.connection", 
                Placeholder.unparsed("state", redis.state().name()),
                Placeholder.unparsed("duration", formatDuration(redis.stateDuration()))));

        // Transport Anomalies
        source.sendMessage(render("doctor.transport", 
                Placeholder.unparsed("pending", Long.toString(diagnostics.pendingEntries(self))),
                Placeholder.unparsed("dead_letters", Long.toString(consumer.deadLetterCount())),
                Placeholder.unparsed("dispatch_queue", Integer.toString(consumer.pendingSignals())),
                Placeholder.unparsed("reaps", Long.toString(reaper.reapCount()))));

        // Placement Health
        String ageStr = healthRegistry.oldestSnapshotAge()
                .map(this::formatDuration)
                .orElse("Pending");
        
        source.sendMessage(render("doctor.health", 
                Placeholder.unparsed("missing", Integer.toString(healthRegistry.missingHealthCount())),
                Placeholder.unparsed("oldest_age", ageStr)));

        // Decisions
        source.sendMessage(render("doctor.decisions_header"));
        var decisions = decisionBuffer.recent();
        if (decisions.isEmpty()) {
            source.sendMessage(render("doctor.decisions_empty"));
        } else {
            for (var decision : decisions) {
                if (decision instanceof PlacementDecision.Success success) {
                    source.sendMessage(render("doctor.decision_success", 
                            Placeholder.unparsed("role", success.role().value()),
                            Placeholder.unparsed("node", success.chosenNode().value())));
                } else if (decision instanceof PlacementDecision.Refused refused) {
                    source.sendMessage(render("doctor.decision_refused", 
                            Placeholder.unparsed("role", refused.role().value()),
                            Placeholder.unparsed("reason", refused.refusalReason())));
                }
            }
        }
    }

    private String formatDuration(Duration d) {
        long s = d.getSeconds();
        return String.format("%02d:%02d", (s % 3600) / 60, s % 60);
    }

    private record ReloadAck(boolean ok, String reason) {}

    private void reload(CommandSource source, String[] args) {
        if (args.length < 2) {
            configurations.reloadAll();
            source.sendMessage(render("command.reloaded"));
            return;
        }
        
        ServerRole targetRole = new ServerRole(args[1]);
        List<ServerIdentity> targets = presence.cachedFleet().withRole(targetRole);

        if (targets.isEmpty()) {
            source.sendMessage(render("reload.no_targets", Placeholder.unparsed("role", targetRole.value())));
            return;
        }

        source.sendMessage(render("reload.dispatching", Placeholder.unparsed("count", String.valueOf(targets.size()))));

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        CompletableFuture<?>[] futures = targets.stream()
            .map(target -> {
                Envelope req = Envelope.request(PillarMessageTypes.RELOAD_CONFIG, self, "{}");
                return requestSender.send(target.id(), req, Duration.ofSeconds(5)).handle((reply, error) -> {
                    scheduler.runSync(() -> {
                        if (error == null) {
                            ReloadAck ack = GSON.fromJson(reply.payload(), ReloadAck.class);
                            if (ack != null && !ack.ok()) {
                                failures.incrementAndGet();
                                source.sendMessage(render("reload.failed", 
                                        Placeholder.unparsed("server", target.id().value()),
                                        Placeholder.unparsed("message", ack.reason() != null ? ack.reason() : "Unknown error")));
                            } else {
                                successes.incrementAndGet();
                                source.sendMessage(render("reload.success", Placeholder.unparsed("server", target.id().value())));
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
                            source.sendMessage(render("reload.failed", 
                                    Placeholder.unparsed("server", target.id().value()),
                                    Placeholder.unparsed("message", msg)));
                        }
                    });
                    return null;
                });
            }).toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).thenRun(() -> {
            scheduler.runSync(() -> {
                source.sendMessage(render("reload.summary", 
                        Placeholder.unparsed("success", String.valueOf(successes.get())),
                        Placeholder.unparsed("failures", String.valueOf(failures.get()))));
            });
        });
    }

    private Component render(String key, TagResolver... placeholders) {
        return MINI.deserialize(lang.get(key), placeholders);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0 || args.length == 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            return Stream.of("fleet", "status", "doctor", "ping", "reload")
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        } else if (args.length == 2 && args[0].equalsIgnoreCase("ping")) {
            return presence.cachedFleet().members().stream()
                    .map(node -> node.id().value())
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
