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

    public double getDouble(String path, double fallback) {
        Object resolved = resolve(path);
        if (resolved instanceof Double d) {
            return d;
        }
        if (resolved instanceof Number n) {
            return n.doubleValue();
        }
        return fallback;
    }

    private Object resolve(String path) {
        String[] segments = path.split("\\.");
        String leafKey = segments[segments.length - 1];

        Map<String, Object> parentSection = navigateToParentSection(segments);
        return parentSection == null ? null : parentSection.get(leafKey);
    }

    // Descends through every segment except the last. Returns null if any intermediate
    // segment is absent or is not itself a nested section.
    private Map<String, Object> navigateToParentSection(String[] segments) {
        Map<String, Object> section = data;
        for (int depth = 0; depth < segments.length - 1; depth++) {
            section = asSection(section.get(segments[depth]));
            if (section == null) {
                return null;
            }
        }
        return section;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asSection(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
