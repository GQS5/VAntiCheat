package site.vackstudio.vanticheat.platform;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;

/** Routes each operation to the scheduler required by the detected runtime. */
public final class PaperFoliaScheduler implements Scheduler {
    private final Plugin plugin;
    private final Server server;
    private final Platform platform;

    public PaperFoliaScheduler(Plugin plugin, Server server, Platform platform) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = Objects.requireNonNull(server, "server");
        this.platform = Objects.requireNonNull(platform, "platform");
        if (platform == Platform.UNKNOWN) throw new IllegalArgumentException("Unknown platform");
    }

    @Override
    public TaskHandle runAtEntity(EntityTarget target, Runnable task) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(task, "task");
        Entity entity = require(target.nativeEntity(), Entity.class, "entity");
        if (platform == Platform.FOLIA) {
            ScheduledTask scheduled = entity.getScheduler().run(plugin,
                    ignored -> task.run(), () -> { });
            return foliaHandle(scheduled);
        }
        return bukkitHandle(server.getScheduler().runTask(plugin, task));
    }

    @Override
    public TaskHandle runAtLocation(RegionTarget target, Runnable task) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(task, "task");
        World world = require(target.nativeWorld(), World.class, "world");
        if (platform == Platform.FOLIA) {
            ScheduledTask scheduled = server.getRegionScheduler().run(plugin, world,
                    target.chunkX(), target.chunkZ(), ignored -> task.run());
            return foliaHandle(scheduled);
        }
        return bukkitHandle(server.getScheduler().runTask(plugin, task));
    }

    @Override
    public TaskHandle runGlobal(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (platform == Platform.FOLIA) {
            ScheduledTask scheduled = server.getGlobalRegionScheduler().run(plugin,
                    ignored -> task.run());
            return foliaHandle(scheduled);
        }
        return bukkitHandle(server.getScheduler().runTask(plugin, task));
    }

    @Override
    public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
        Objects.requireNonNull(task, "task");
        long delay = Math.max(1L, delayTicks);
        if (platform == Platform.FOLIA) {
            ScheduledTask scheduled = server.getGlobalRegionScheduler().runDelayed(plugin,
                    ignored -> task.run(), delay);
            return foliaHandle(scheduled);
        }
        return bukkitHandle(server.getScheduler().runTaskLater(plugin, task, delay));
    }

    @Override
    public TaskHandle runAsync(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (platform == Platform.FOLIA) {
            ScheduledTask scheduled = server.getAsyncScheduler().runNow(plugin,
                    ignored -> task.run());
            return foliaHandle(scheduled);
        }
        return bukkitHandle(server.getScheduler().runTaskAsynchronously(plugin, task));
    }

    @Override
    public void shutdown() {
        if (platform == Platform.FOLIA) {
            server.getGlobalRegionScheduler().cancelTasks(plugin);
            server.getAsyncScheduler().cancelTasks(plugin);
        } else {
            server.getScheduler().cancelTasks(plugin);
        }
    }

    private static TaskHandle bukkitHandle(BukkitTask task) {
        return new TaskHandle() {
            @Override public void cancel() { task.cancel(); }
            @Override public boolean cancelled() { return task.isCancelled(); }
        };
    }

    private static TaskHandle foliaHandle(ScheduledTask task) {
        return new TaskHandle() {
            @Override public void cancel() { task.cancel(); }
            @Override public boolean cancelled() { return task.isCancelled(); }
        };
    }

    private static <T> T require(Object value, Class<T> type, String name) {
        if (!type.isInstance(value)) throw new IllegalArgumentException("Invalid " + name + " target");
        return type.cast(value);
    }
}
