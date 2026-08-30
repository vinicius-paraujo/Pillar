package br.com.markineo.pillar.redis.transport;

import br.com.markineo.pillar.config.Configurations;
import br.com.markineo.pillar.core.identity.ServerId;
import br.com.markineo.pillar.core.task.CorrelationId;
import br.com.markineo.pillar.core.task.Envelope;
import br.com.markineo.pillar.core.task.EnvelopeCodec;
import br.com.markineo.pillar.core.task.PillarMessageTypes;
import br.com.markineo.pillar.logger.PillarLogger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReloadConfigHandlerTest {

    @Test
    void testHandleReloadsConfigAndRepliesWithAckIfCorrelationPresent() {
        // Arrange
        FakeConfigurations configurations = new FakeConfigurations();
        FakeStreamPublisher publisher = new FakeStreamPublisher();
        ServerId self = new ServerId("self-node");
        PillarLogger logger = new PillarLogger(LoggerFactory.getLogger("reload-test"));
        ReloadConfigHandler handler = new ReloadConfigHandler(configurations, self, publisher, logger);
        
        Envelope request = Envelope.request(PillarMessageTypes.RELOAD_CONFIG, new ServerId("sender-node"), "{}");
        CorrelationId correlation = request.correlationId().orElseThrow();
        
        // Act
        handler.handle(request, null);

        // Assert
        assertTrue(configurations.reloaded, "Configurations should be reloaded");
        assertEquals(1, publisher.published.size(), "Should publish one reply");
        
        Envelope reply = publisher.published.get(0);
        assertEquals(PillarMessageTypes.RELOAD_CONFIG_ACK, reply.type(), "Reply type should be ACK");
        assertTrue(reply.correlationId().isPresent(), "Reply should have correlation");
        assertEquals(correlation, reply.correlationId().get(), "Correlation ID should match");
        assertEquals(self, reply.senderId(), "Sender should be self-node");
        assertTrue(logger.history().stream()
                .anyMatch(entry -> entry.message().equals("Reload completed successfully.")));
    }

    @Test
    void testHandleReloadsConfigAndDoesNotReplyIfNoCorrelation() {
        // Arrange
        FakeConfigurations configurations = new FakeConfigurations();
        FakeStreamPublisher publisher = new FakeStreamPublisher();
        ServerId self = new ServerId("self-node");
        PillarLogger logger = new PillarLogger(LoggerFactory.getLogger("reload-test"));
        ReloadConfigHandler handler = new ReloadConfigHandler(configurations, self, publisher, logger);
        
        Envelope request = Envelope.oneWay(PillarMessageTypes.RELOAD_CONFIG, new ServerId("sender-node"), "{}");
        
        // Act
        handler.handle(request, null);

        // Assert
        assertTrue(configurations.reloaded, "Configurations should be reloaded");
        assertTrue(publisher.published.isEmpty(), "Should not publish any reply if there is no correlation");
    }

    // --- Test Doubles ---

    private static class FakeConfigurations extends Configurations {
        boolean reloaded = false;
        
        public FakeConfigurations() {
            super(null); // Pass null FileLoader
        }

        @Override
        public void reloadAll() {
            this.reloaded = true;
        }
    }

    private static class FakeStreamPublisher extends StreamPublisher {
        final List<Envelope> published = new ArrayList<>();

        public FakeStreamPublisher() {
            super(null, null);
        }

        @Override
        public void publish(ServerId target, Envelope envelope) {
            this.published.add(envelope);
        }
    }
}
