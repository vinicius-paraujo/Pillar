package com.markineo.pillar.config;

import com.markineo.pillar.error.ConfigurationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationFileTest {

    @TempDir
    Path directory;

    private ConfigurationFile config;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(directory.resolve("config.yml"), """
                server:
                  name: "skyblock-1"
                  role: "skyblock"
                redis:
                  port: 6379
                debug: true
                """);
        config = new ConfigurationFile(new FileLoader(directory), "config.yml");
    }

    @Test
    void readsNestedStringByDottedPath() {
        assertEquals("skyblock-1", config.requireString("server.name"));
        assertEquals("skyblock", config.getString("server.role", "fallback"));
    }

    @Test
    void readsNestedIntAndBoolean() {
        assertEquals(6379, config.requireInt("redis.port"));
        assertTrue(config.getBoolean("debug", false));
    }

    @Test
    void fallsBackWhenPathIsAbsent() {
        assertEquals("default", config.getString("server.absent", "default"));
        assertEquals(25565, config.getInt("redis.missing", 25565));
        assertFalse(config.getBoolean("nope.here", false));
    }

    @Test
    void requireThrowsWithFileAndPathWhenMissing() {
        ConfigurationException thrown = assertThrows(ConfigurationException.class,
                () -> config.requireString("redis.host"));
        assertTrue(thrown.getMessage().contains("redis.host"));
        assertTrue(thrown.getMessage().contains("config.yml"));
    }

    @Test
    void descendingThroughANonSectionYieldsFallback() {
        // "server.name" is a leaf; treating it as a section must not blow up.
        assertEquals("x", config.getString("server.name.deeper", "x"));
    }
}
