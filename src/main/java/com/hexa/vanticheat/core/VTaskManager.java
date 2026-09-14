package com.hexa.vanticheat.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Folia-correct scheduler abstraction.
 *
 * <ul>
 *   <li>Region work: {@link #region(Location, Consumer)} / {@link #region(Consumer)}</li>
 *   <li>Entity work: {@link #entity(Entity, Consumer)}</li>
 *   <li>Async IO: {@link #async(Runnable)}, {@link #runAsyncTimer(Runnable, long, long)}</li>
 * </ul>
 * Never expose the global Bukkit scheduler for world/entity mutation.
 */
public final class VTaskManager {

    private final VAntiCheat plugin;

    public VTaskManager(VAntiCheat plugin) {
        this.plugin = plugin;
    }

    /** Run on the region owning the location (Folia RegionScheduler). */
    public void region(Location loc, Consumer<org.bukkit.scheduler.BukkitTask> run) {
        try {
            Bukkit.getRegionScheduler().run(plugin, loc, t -> run.accept(null));
        } catch (Throwable th) {
            // Fallback for tests / non-Folia: run directly if scheduler unavailable.
            run.accept(null);
        }
    }

    /** Run on the global region (spawn region) for non-positional work that must stay on a region thread. */
    public void region(Consumer<org.bukkit.scheduler.BukkitTask> run) {
        try {
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> run.accept(null));
        } catch (Throwable th) {
            run.accept(null);
        }
    }

    /** Delayed run on a region. */
    public void regionDelayed(Location loc, Consumer<org.bukkit.scheduler.BukkitTask> run, long delayTicks) {
        try {
            Bukkit.getRegionScheduler().runDelayed(plugin, loc, t -> run.accept(null), delayTicks);
        } catch (Throwable th) {
            run.accept(null);
        }
    }

    /** Run on the entity's scheduler (Folia EntityScheduler via Player#getScheduler / Entity scheduler). */
    public void entity(Entity entity, Consumer<org.bukkit.scheduler.BukkitTask> run) {
        try {
            entity.getScheduler().run(plugin, t -> run.accept(null), null);
        } catch (Throwable th) {
            run.accept(null);
        }
    }

    /** Kick must run on the player's scheduler in Folia. Falls back to direct call. */
    public void kick(org.bukkit.entity.Player player, String reason) {
        Runnable r = () -> {
            try {
                player.kick(net.kyori.adventure.text.Component.text(reason));
            } catch (Throwable th) {
                try {
                    player.kickPlayer(reason);
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            player.getScheduler().run(plugin, t -> r.run(), null);
        } catch (Throwable th) {
            r.run();
        }
    }

    /** Async work (IO, persistence, heavy math). */
    public void async(Runnable run) {
        try {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> run.run());
        } catch (Throwable th) {
            run.run();
        }
    }

    /** Repeating async timer. Period in ticks (converted to ms). */
    public void runAsyncTimer(Runnable run, long delayTicks, long periodTicks) {
        try {
            long delayMs = delayTicks * 50L;
            long periodMs = periodTicks * 50L;
            Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> run.run(), delayMs, periodMs, TimeUnit.MILLISECONDS);
        } catch (Throwable th) {
            // tests: ignore
        }
    }

    public void shutdown() {
        try {
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        } catch (Throwable ignored) {
        }
        try {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        } catch (Throwable ignored) {
        }
    }
}
