package com.markineo.pillar.config;

import com.markineo.pillar.error.ConfigurationException;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class FileLoader {

    private final Path directory;

    public FileLoader(Path directory) {
        this.directory = directory;
    }

    public Map<String, Object> load(String fileName) {
        Path file = directory.resolve(fileName);
        if (Files.notExists(file)) {
            copyBundledDefault(fileName, file);
        }

        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> data = new Yaml().load(in);
            return data != null ? data : Map.of();
        } catch (IOException e) {
            throw new ConfigurationException("Failed to read '" + fileName + "'.", e);
        }
    }

    private void copyBundledDefault(String fileName, Path target) {
        try (InputStream resource = getClass().getClassLoader().getResourceAsStream(fileName)) {
            if (resource == null) {
                throw new ConfigurationException("Missing bundled default for '" + fileName + "'.");
            }
            Files.createDirectories(target.getParent());
            Files.copy(resource, target);
        } catch (IOException e) {
            throw new ConfigurationException("Failed to write default '" + fileName + "'.", e);
        }
    }
}
