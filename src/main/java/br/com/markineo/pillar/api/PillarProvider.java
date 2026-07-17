package br.com.markineo.pillar.api;

/**
 * Static access point to the running {@link Pillar} instance. Pillar itself calls
 * {@link #register} and {@link #unregister} as it starts up and shuts down; consumers
 * only ever call {@link #get()}.
 */
public final class PillarProvider {
    private static volatile Pillar instance;

    private PillarProvider() {}

    /**
     * Returns the running {@link Pillar} instance.
     *
     * @throws IllegalStateException if called before Pillar has finished loading, or
     *     if the calling plugin does not declare Pillar as a dependency
     */
    public static Pillar get() {
        Pillar p = instance;
        if (p == null) {
            throw new IllegalStateException("Pillar is not enabled. Make sure your plugin declares Pillar as a dependency (plugin.yml on Paper, velocity-plugin.json on Velocity) and calls PillarProvider.get() after Pillar is fully loaded.");
        }
        return p;
    }

    /**
     * Registers the running {@link Pillar} instance. Called once by Pillar itself
     * during startup.
     *
     * @throws IllegalStateException if an instance is already registered
     */
    public static void register(Pillar pillar) {
        if (instance != null) {
            throw new IllegalStateException("Pillar is already registered.");
        }
        instance = pillar;
    }

    /**
     * Clears the registered instance. Called by Pillar itself during shutdown.
     */
    public static void unregister() {
        instance = null;
    }
}
