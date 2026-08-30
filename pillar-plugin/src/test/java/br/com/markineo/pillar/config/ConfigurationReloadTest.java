package br.com.markineo.pillar.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

// PIL-15 claims `/pillar reload` refreshes message text because Lang holds the cached
// ConfigurationFile instances and reloadAll swaps their snapshots. That claim was never
// asserted anywhere, so this pins the whole chain end to end against a real file.
class ConfigurationReloadTest {

    @TempDir
    Path directory;

    @Test
    void reloadPicksUpMessageTextChangedOnDisk() throws IOException {
        writeLanguage("pt-br", "old text");
        writeLanguage("en-us", "fallback text");

        Configurations configurations = new Configurations(new FileLoader(directory));
        Lang lang = new Lang(configurations, "pt-br");

        assertEquals("old text", lang.get("command.reloaded"));

        writeLanguage("pt-br", "new text");
        configurations.reloadAll();

        assertEquals("new text", lang.get("command.reloaded"));
    }

    @Test
    void reloadPicksUpAKeyAddedToTheFallbackAfterStartup() throws IOException {
        writeLanguage("pt-br", "translated");
        writeLanguage("en-us", "fallback text");

        Configurations configurations = new Configurations(new FileLoader(directory));
        Lang lang = new Lang(configurations, "pt-br");

        // A key present in neither file surfaces as itself, by Lang's own contract.
        assertEquals("command.usage", lang.get("command.usage"));

        Files.writeString(directory.resolve("lang/en-us.yml"),
                "command:\n  reloaded: \"fallback text\"\n  usage: \"added later\"\n");
        configurations.reloadAll();

        assertEquals("added later", lang.get("command.usage"));
    }

    private void writeLanguage(String locale, String reloadedMessage) throws IOException {
        Path file = directory.resolve("lang/" + locale + ".yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "command:\n  reloaded: \"" + reloadedMessage + "\"\n");
    }
}
