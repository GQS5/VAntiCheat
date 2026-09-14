package com.hexa.vanticheat.antixray;

import com.hexa.vanticheat.core.VAntiCheat;
import com.hexa.vanticheat.core.VConfig;
import com.hexa.vanticheat.core.VProfiler;
import com.hexa.vanticheat.core.VTaskManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Event-driven ore tracking. All work is O(1) per break; heavy analysis runs
 * at most once per N breaks per player (counter-gated), never per tick.
 */
public final class XrayListener implements Listener {

    private final VAntiCheat plugin;
    private final VConfig config;
    private final OreExposureTracker tracker;
    private final MiningAnalyzer analyzer;
    private final XrayConfidenceEngine engine;
    private final VTaskManager tasks;
    private final VProfiler profiler;
    private final java.util.Map<java.util.UUID, Integer> sinceEval = new java.util.concurrent.ConcurrentHashMap<>();

    public XrayListener(VAntiCheat plugin, VConfig config, OreExposureTracker tracker,
                        MiningAnalyzer analyzer, XrayConfidenceEngine engine,
                        VTaskManager tasks, VProfiler profiler) {
        this.plugin = plugin;
        this.config = config;
        this.tracker = tracker;
        this.analyzer = analyzer;
        this.engine = engine;
        this.tasks = tasks;
        this.profiler = profiler;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!config.getBoolean("antixray.enabled", true)) return;
        if (config.exempt(e.getPlayer())) return;
        long t0 = System.nanoTime();
        try {
            var d = tracker.track(e.getPlayer().getUniqueId(), e.getBlock());
            analyzer.onBlockBreak(e.getPlayer().getUniqueId(), e.getBlock().getLocation(), d != null, d != null && !d.exposed());
            if (d != null && d.rare()) analyzer.onRare(e.getPlayer().getUniqueId(), e.getBlock().getLocation());
            int n = sinceEval.merge(e.getPlayer().getUniqueId(), 1, Integer::sum);
            if (n >= 16) {
                sinceEval.put(e.getPlayer().getUniqueId(), 0);
                var stats = tracker.snapshot(e.getPlayer().getUniqueId());
                var analysis = analyzer.analyze(e.getPlayer().getUniqueId());
                engine.evaluate(e.getPlayer(), stats, analysis);
            }
        } finally {
            profiler.record("antixray.break", System.nanoTime() - t0);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        tracker.purge(e.getPlayer().getUniqueId());
        analyzer.purge(e.getPlayer().getUniqueId());
        sinceEval.remove(e.getPlayer().getUniqueId());
    }
}
