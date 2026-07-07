package com.markineo.pillar.config;

import com.markineo.pillar.error.ConfigurationException;

import java.util.Map;

public final class ConfigurationFile {

    private final FileLoader loader;
    private final String fileName;
    private volatile Map<String, Object> data;

    public ConfigurationFile(FileLoader loader, String fileName) {
        this.loader = loader;
        this.fileName = fileName;
        this.data = loader.load(fileName);
    }

    public void reload() {
        this.data = loader.load(fileName);
    }

    public String getString(String path, String fallback) {
        return resolve(path) instanceof String value ? value : fallback;
    }

    public String requireString(String path) {
        if (!(resolve(path) instanceof String value) || value.isBlank()) {
            throw new ConfigurationException("Missing or empty '" + path + "' in " + fileName + ".");
        }
        return value;
    }

    public int getInt(String path, int fallback) {
        return resolve(path) instanceof Integer value ? value : fallback;
    }

    public int requireInt(String path) {
        if (!(resolve(path) instanceof Integer value)) {
            throw new ConfigurationException("Missing or non-numeric '" + path + "' in " + fileName + ".");
        }
        return value;
    }

    public boolean getBoolean(String path, boolean fallback) {
        return resolve(path) instanceof Boolean value ? value : fallback;
    }

    private Object resolve(String path) {
        Map<String, Object> current = data;
        String[] segments = path.split("\\.");

        for (int i = 0; i < segments.length - 1; i++) {
            if (!(current.get(segments[i]) instanceof Map<?, ?> section)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) section;
            current = typed;
        }

        return current.get(segments[segments.length - 1]);
    }
}
