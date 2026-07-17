package br.com.markineo.pillar.composition;

import com.google.gson.Gson;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.api.routing.RouteOutcome;
import br.com.markineo.pillar.core.placement.RoutePlayerRequest;
import br.com.markineo.pillar.core.placement.RoutePlayerResponse;
import br.com.markineo.pillar.core.task.CorrelationRegistry;
import br.com.markineo.pillar.core.task.Envelope;
import br.com.markineo.pillar.core.task.EnvelopeCodec;
import br.com.markineo.pillar.core.task.HandlerRegistry;
import br.com.markineo.pillar.core.task.EnvelopeHandler;
import br.com.markineo.pillar.core.task.MessageType;
import br.com.markineo.pillar.core.task.PillarMessageTypes;
import br.com.markineo.pillar.redis.RedisIntegrationTest;
import br.com.markineo.pillar.redis.transport.JsonEnvelopeCodec;
import br.com.markineo.pillar.redis.transport.RequestSender;
import br.com.markineo.pillar.redis.transport.StreamConsumer;
import br.com.markineo.pillar.redis.transport.StreamPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.markineo.pillar.redis.RedisKeys;
import redis.clients.jedis.Jedis;

class TaskRoutingCompositionIntegrationTest extends RedisIntegrationTest {

    private static final MessageType CREATE_ISLAND = new MessageType("minigame.create_island");

    // Nodes
    private Node proxyNode;
    private Node hubNode;
    private Node minigameNode;

    private EnvelopeCodec codec;
    private Gson gson;

    @BeforeEach
    void setupNodes() {
        gson = new Gson();
        codec = new JsonEnvelopeCodec(gson);

        proxyNode = new Node(new ServerId("proxy"));
        hubNode = new Node(new ServerId("hub"));
        minigameNode = new Node(new ServerId("minigame"));

        setupProxyHandlers();
        setupMinigameHandlers();

        proxyNode.consumer.start();
        hubNode.consumer.start();
        minigameNode.consumer.start();

        await("proxy group", () -> groupExists(proxyNode.id));
        await("hub group", () -> groupExists(hubNode.id));
        await("minigame group", () -> groupExists(minigameNode.id));
    }

    private boolean groupExists(ServerId node) {
        try (Jedis jedis = connector.getResource()) {
            return jedis.xinfoGroups(RedisKeys.inbox(node)).stream()
                    .anyMatch(group -> "pillar".equals(group.getName()));
        } catch (Exception e) {
            return false;
        }
    }

    @AfterEach
    void stopNodes() {
        proxyNode.consumer.close();
        hubNode.consumer.close();
        minigameNode.consumer.close();
    }

    private void setupProxyHandlers() {
        proxyNode.handlers.register(new EnvelopeHandler() {
            @Override
            public MessageType type() {
                return PillarMessageTypes.ROUTE_PLAYER;
            }

            @Override
            public void handle(Envelope envelope, EnvelopeCodec codec) {
                if (envelope.correlationId().isPresent()) {
                    // Simulate that the proxy successfully moved the player
                    String replyPayload = gson.toJson(RoutePlayerResponse.of(RouteOutcome.SUCCESS));
                    Envelope response = Envelope.response(PillarMessageTypes.ROUTE_PLAYER, envelope.correlationId().get(), proxyNode.id, replyPayload);
                    proxyNode.publisher.publish(envelope.senderId(), response);
                }
            }
        });
    }

    private void setupMinigameHandlers() {
        minigameNode.handlers.register(new EnvelopeHandler() {
            @Override
            public MessageType type() {
                return CREATE_ISLAND;
            }

            @Override
            public void handle(Envelope envelope, EnvelopeCodec codec) {
                // Simulate task completion
                if (envelope.correlationId().isPresent()) {
                    Envelope response = Envelope.response(CREATE_ISLAND, envelope.correlationId().get(), minigameNode.id, "{\"status\":\"OK\"}");
                    minigameNode.publisher.publish(envelope.senderId(), response);
                }
            }
        });
    }

    @Test
    void testTaskOutcomeDrivesPlayerMove() throws Exception {
        UUID playerId = UUID.randomUUID();

        // 1. Hub dispatches a CREATE_ISLAND task to the Minigame node
        Envelope taskReq = Envelope.request(CREATE_ISLAND, hubNode.id, "{\"owner\":\"TestPlayer\"}");
        CompletableFuture<Envelope> taskFuture = hubNode.requestSender.send(minigameNode.id, taskReq, Duration.ofSeconds(5));

        // 2. We compose the pipeline: when task finishes, route the player
        CompletableFuture<RouteOutcome> finalOutcome = taskFuture.thenCompose(taskResponse -> {
            String payload = gson.toJson(new RoutePlayerRequest(playerId, "minigame", null));
            Envelope routeReq = Envelope.request(PillarMessageTypes.ROUTE_PLAYER, hubNode.id, payload);
            return hubNode.requestSender.send(proxyNode.id, routeReq, Duration.ofSeconds(5))
                    .thenApply(responseEnv -> {
                        RoutePlayerResponse rpRes = gson.fromJson(responseEnv.payload(), RoutePlayerResponse.class);
                        return RouteOutcome.byName(rpRes.outcome()).orElseThrow();
                    });
        });

        // 3. Await outcome
        // This simulates wait because StreamConsumers run in the background
        RouteOutcome outcome = finalOutcome.get(10, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(RouteOutcome.SUCCESS, outcome, "Player should be successfully routed after task completion");
    }

    class Node {
        final ServerId id;
        final StreamPublisher publisher;
        final StreamConsumer consumer;
        final HandlerRegistry handlers;
        final CorrelationRegistry correlations;
        final RequestSender requestSender;

        Node(ServerId id) {
            this.id = id;
            this.publisher = new StreamPublisher(connector, codec);
            this.correlations = new CorrelationRegistry(executors.newSingleThreadScheduled("correlations-" + id.value()));
            this.handlers = new HandlerRegistry(correlations);
            this.consumer = new StreamConsumer(connector, codec, id, executors, logger, handlers.asSink(codec, logger), Duration.ofMinutes(5), 2, 100);
            this.requestSender = new RequestSender(this.publisher, this.correlations);
        }
    }
}
