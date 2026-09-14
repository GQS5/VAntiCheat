package com.hexa.vanticheat.antiesp;

import com.hexa.vanticheat.core.VConfig;
import com.hexa.vanticheat.evidence.ConfidenceEngine;
import com.hexa.vanticheat.evidence.EvidenceManager;
import org.bukkit.entity.Player;

/**
 * Minimap/radar policy layer. Distinguishes cosmetic map vs radar vs entity
 * radar vs cave-map via per-mod config flags. Enforcement itself happens in
 * ModDetector (channel evidence) + PunishmentManager; this class only adds
 * behavior correlation notes (e.g. player navigates directly to hidden
 * entities while a radar mod channel is present — handled by confidence).
 */
public final class RadarProtection {

    private final VConfig config;
    private final EvidenceManager evidence;
    private final ConfidenceEngine confidence;

    public RadarProtection(VConfig config, EvidenceManager evidence, ConfidenceEngine confidence) {
        this.config = config;
        this.evidence = evidence;
        this.confidence = confidence;
    }

    public void evaluate(Player p) {
        if (!config.getBoolean("anti_esp.minimap.enabled", true)) return;
        // Policy lookup only; no packets, no allocations in common path.
    }

    /** True when the given mod id is forbidden by minimap/radar policy. */
    public boolean isForbidden(String modId) {
        return config.getBoolean("anti_esp.minimap.forbidden." + modId, false);
    }
}
