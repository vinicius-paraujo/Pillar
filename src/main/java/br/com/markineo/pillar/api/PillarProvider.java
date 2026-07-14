package br.com.markineo.pillar.api;

public final class PillarProvider {
    private static volatile Pillar instance;

    private PillarProvider() {}

    public static Pillar get() {
        Pillar p = instance;
        if (p == null) {
            throw new IllegalStateException("Pillar is not enabled. Make sure your plugin depends on Pillar in plugin.yml and calls PillarProvider.get() after Pillar is fully loaded.");
        }
        return p;
    }

    public static void register(Pillar pillar) {
        if (instance != null) {
            throw new IllegalStateException("Pillar is already registered.");
        }
        instance = pillar;
    }

    public static void unregister() {
        instance = null;
    }
}
