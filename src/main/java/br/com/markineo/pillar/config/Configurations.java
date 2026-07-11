package br.com.markineo.pillar.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Configurations {

    private final FileLoader loader;
    private final Map<String, ConfigurationFile> cache = new ConcurrentHashMap<>();

    public Configurations(FileLoader loader) {
        this.loader = loader;
    }

    public ConfigurationFile get(String name) {
        return cache.computeIfAbsent(name, key -> new ConfigurationFile(loader, key));
    }

    public void reloadAll() {
        cache.values().forEach(ConfigurationFile::reload);
    }
}
