package com.markineo.pillar.config;

public final class Lang {

    private static final String FALLBACK_LOCALE = "en-us";

    private final ConfigurationFile active;
    private final ConfigurationFile fallback;

    public Lang(Configurations configurations, String locale) {
        this.active = configurations.get(fileFor(locale));
        this.fallback = configurations.get(fileFor(FALLBACK_LOCALE));
    }

    public String get(String key) {
        String value = active.getString(key, null);
        if (value != null) {
            return value;
        }
        String fallbackValue = fallback.getString(key, null);
        // A missing key surfaces as itself so operators can spot the gap in-game.
        return fallbackValue != null ? fallbackValue : key;
    }

    private static String fileFor(String locale) {
        return "lang/" + locale + ".yml";
    }
}
