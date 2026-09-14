package com.hexa.vanticheat.antiesp;

import com.hexa.vanticheat.core.VAntiCheat;
import com.hexa.vanticheat.core.VConfig;
import com.hexa.vanticheat.core.VLogger;
import com.hexa.vanticheat.core.VProfiler;
import com.hexa.vanticheat.core.VTaskManager;
import com.hexa.vanticheat.evidence.ConfidenceEngine;
import com.hexa.vanticheat.evidence.EvidenceManager;
import com.hexa.vanticheat.evidence.EvidenceRecord;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AntiESP — honest architecture:
 *
 * <p>INFORMATION PROTECTION (server-side, real): VAntiCheat documents and
 * verifies Paper's built-in anti-xray (ore obfuscation) instead of
 * re-implementing chunk packets. Player/entity ESP cannot be prevented by
 * hiding entities server-side without breaking gameplay (vanilla clients need
 * entity spawns), so this module implements:
 * <ul>
 *   <li>Radar/minimap policy enforcement (forbidden mod channels → see ModDetector).</li>
 *   <li>Behavioral tracking analysis: rotation correlation toward hidden players
 *       (see {@link PlayerTrackingAnalyzer}).</li>
 *   <li>Underground entity-detection heuristics (mining directly toward hidden spawners/players).</li>
 * </ul>
 * No fake "entity hiding" is claimed: see README limitations.
 */
public final class AntiESPManager implements Listener {

    private final VAntiCheat plugin;
    private final VConfig config;
    private final VLogger log;
    private final EvidenceManager evidence;
    private final ConfidenceEngine confidence;
    private final VTaskManager tasks;
    private final VProfiler profiler;
    private final PlayerTrackingAnalyzer tracking;
    private final RadarProtection radar;
    private final Map<UUID, Long> lastEval = new ConcurrentHashMap<>();

    public AntiESPManager(VAntiCheat plugin, VConfig config, VLogger log,
                          EvidenceManager evidence, ConfidenceEngine confidence,
                          VTaskManager tasks, VProfiler profiler) {
        this.plugin = plugin;
        this.config = config;
        this.log = log;
        this.evidence = evidence;
        this.confidence = confidence;
        this.tasks = tasks;
        this.profiler = profiler;
        this.tracking = new PlayerTrackingAnalyzer(config);
        this.radar = new RadarProtection(config, evidence, confidence);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!config.getBoolean("antiesp.enabled", true)) return;
        Player p = e.getPlayer();
        if (config.exempt(p)) return;
        Location to = e.getTo();
        if (to == null) return;
        long t0 = System.nanoTime();
        try {
            tracking.sample(p, to);
            // Throttle: full evaluation at most every 3s per player.
            long now = System.currentTimeMillis();
            Long last = lastEval.get(p.getUniqueId());
            if (last != null && now - last < 3000) return;
            lastEval.put(p.getUniqueId(), now);
            var res = tracking.evaluate(p);
            if (res.suspicious()) {
                int conf = confidence.addSignal(p.getUniqueId(), "esp_behavior", res.weight());
                evidence.report(EvidenceRecord.builder()
                        .player(p.getUniqueId(), p.getName())
                        .check("AntiESP").detection(res.reason())
                        .method("Behavior").confidence(conf).violation(0)
                        .brand(plugin.clients() == null ? "?" : plugin.clients().brandOf(p.getUniqueId()))
                        .matched("").values(res.values()).build());
            }
            radar.evaluate(p);
        } finally {
            profiler.record("antiesp.move", System.nanoTime() - t0);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        tracking.purge(e.getPlayer().getUniqueId());
        lastEval.remove(e.getPlayer().getUniqueId());
    }

    public void decay() {
        tracking.decay();
    }
}
