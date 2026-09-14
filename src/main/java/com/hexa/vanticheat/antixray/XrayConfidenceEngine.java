package com.hexa.vanticheat.antixray;

import com.hexa.vanticheat.core.VConfig;
import com.hexa.vanticheat.evidence.ConfidenceEngine;
import com.hexa.vanticheat.evidence.EvidenceManager;
import com.hexa.vanticheat.evidence.EvidenceRecord;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Statistical correlation: no single factor (not even many diamonds) can ban.
 * Requires multiple independent suspicious dimensions simultaneously.
 */
public final class XrayConfidenceEngine {

    private final VConfig config;
    private final EvidenceManager evidence;
    private final ConfidenceEngine confidence;
    private final XrayBaseline baseline = new XrayBaseline();

    public XrayConfidenceEngine(VConfig config, EvidenceManager evidence, ConfidenceEngine confidence) {
        this.config = config;
        this.evidence = evidence;
        this.confidence = confidence;
    }

    public void evaluate(Player player, OreExposureTracker.PlayerOreStats stats, MiningAnalyzer.Analysis a) {
        try {
            if (!config.getBoolean("antixray.enabled", true)) return;            if (a.sampleSize() < config.getInt("antixray.min-sample", 40)) return;
            // False-positive suppression context (§10): creative/spectator, high ping, explosions exempt.
            try {
                if (player.getGameMode() == org.bukkit.GameMode.CREATIVE
                        || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
                if (player.getPing() > 350) return;
            } catch (Throwable ignored) {}
            int signals = 0;
        Map<String, String> vals = new HashMap<>();
        double minHiddenRatio = config.getDouble("antixray.thresholds.hidden-ratio", 0.75);
        double minRarePerHour = config.getDouble("antixray.thresholds.rare-per-hour", 12.0);
        int minStreak = config.getInt("antixray.thresholds.hidden-streak", 4);

        if (stats.total >= 10 && stats.hiddenRatio() >= minHiddenRatio) { signals++; vals.put("hiddenRatio", String.format("%.2f", stats.hiddenRatio())); }
        if (a.hiddenOreRatio() >= minHiddenRatio) { signals++; vals.put("pathHiddenRatio", String.format("%.2f", a.hiddenOreRatio())); }
        if (a.rarePerHour() >= minRarePerHour) { signals++; vals.put("rarePerHour", String.format("%.1f", a.rarePerHour())); }
        if (a.hiddenRareStreak() >= minStreak) { signals++; vals.put("hiddenStreak", String.valueOf(a.hiddenRareStreak())); }
        if (a.avgRareSpacing() > 0 && a.avgRareSpacing() > config.getDouble("antixray.thresholds.min-avg-spacing", 25.0)
                && a.rarePerHour() >= minRarePerHour / 2) { signals++; vals.put("avgSpacing", String.format("%.1f", a.avgRareSpacing())); }

        if (signals < 2) return; // require at least 2 independent dimensions
        // Baseline z-score (§11): compare against server baseline, harder to fool.
        String dim = "overworld";
        try { dim = player.getWorld().getEnvironment().name(); } catch (Throwable ignored) {}
        baseline.observe(player.getUniqueId(), dim, a.rarePerHour());
        double z = baseline.zScore(a.rarePerHour());
        vals.put("zscore", String.format("%.2f", z));
        vals.put("dim", dim);
        if (z < 1.0 && signals < 3) return; // near-baseline + only 2 signals → lucky miner, suppress
        int add = signals >= 4 ? 25 : signals == 3 ? 18 : 12;
        if (z > 3.0) add += 5; // strong outlier bonus, still capped by confidence engine
        UUID id = player.getUniqueId();
        int conf = confidence.addSignal(id, "xray_statistical", add);
        vals.put("signals", signals + "/5");
        evidence.report(EvidenceRecord.builder()
                .player(id, player.getName())
                .detector("AntiXray").check("AntiXray").detection("Suspicious mining correlation (" + signals + " signals)")
                .detectionMethod("Statistical").confidence(conf).violation(0)
                .brand("?").matched("").values(vals).build());
        } catch (Throwable t) { /* Detector unavailable (§29): never crash plugin */ }
    }
}
