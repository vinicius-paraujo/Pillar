package br.com.markineo.pillar.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PillarMessageTest {

    @AfterEach
    void tearDown() {
        PillarMessage.clearRegistry();
    }

    @Test
    void testValidRegistration() {
        PillarMessage<ValidPayload> msg = PillarMessage.of("myplugin:valid", ValidPayload.class);
        assertEquals("myplugin:valid", msg.id());
        assertEquals(ValidPayload.class, msg.payloadClass());
    }
    
    @Test
    void testIdempotentRegistration() {
        PillarMessage<ValidPayload> msg1 = PillarMessage.of("myplugin:idempotent", ValidPayload.class);
        PillarMessage<ValidPayload> msg2 = PillarMessage.of("myplugin:idempotent", ValidPayload.class);
        assertSame(msg1, msg2);
    }

    @Test
    void testMissingNamespace() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            PillarMessage.of("invalid_no_colon", ValidPayload.class);
        });
        assertTrue(ex.getMessage().contains("mandatory"), "Exception message should mention mandatory namespace");
    }

    @Test
    void testCollisionFailFast() {
        PillarMessage.of("plugin:collision", ValidPayload.class);
        
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            PillarMessage.of("plugin:collision", AnotherPayload.class);
        });
        assertTrue(ex.getMessage().contains("already registered"), "Exception message should mention collision");
    }

    @Test
    void testUnserializableComponent() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            PillarMessage.of("plugin:bad_payload", InvalidPayload.class);
        });
        assertTrue(ex.getMessage().contains("unserializable component"), "Exception message should mention unserializable component");
    }
    
    @Test
    void testRecursiveRecord() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            PillarMessage.of("plugin:recursive", RecursivePayload.class);
        });
        assertTrue(ex.getMessage().contains("Recursive records"), "Exception message should prevent recursion");
    }

    // --- Test records ---
    
    public record ValidPayload(
            String name,
            int age,
            UUID uniqueId,
            List<String> items,
            NestedRecord nested
    ) {}
    
    public record NestedRecord(boolean active) {}

    public record AnotherPayload(String name) {}
    
    public record InvalidPayload(String name, Runnable runnable) {}
    
    public record RecursivePayload(String name, RecursivePayload self) {}
}
