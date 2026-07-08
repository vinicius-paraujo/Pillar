package com.markineo.pillar.velocity.tasks;

import com.markineo.pillar.concurrent.PlatformScheduler;

public class VelocityScheduler implements PlatformScheduler {
    @Override
    public void runSync(Runnable task) {
        // Velocity executes everything asynchronously off the proxy threads,
        // so there is no main thread to synchronize with.
        task.run();
    }
}
