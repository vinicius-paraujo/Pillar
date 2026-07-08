package com.markineo.pillar.paper.tasks;

import com.markineo.pillar.concurrent.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class PaperScheduler implements PlatformScheduler {
    private final Plugin plugin;

    public PaperScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runSync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
