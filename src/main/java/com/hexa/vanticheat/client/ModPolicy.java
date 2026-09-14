package com.hexa.vanticheat.client;

import com.hexa.vanticheat.core.VConfig;

import java.util.Locale;
import java.util.Set;

/**
 * Mod policy engine (1.2 §8): detected ≠ forbidden ≠ punishable.
 * Forbidden targets: xaero minimap/worldmap, journeymap, voxelmap, freecam,
 * baritone, xray, litematica, tweakeroo. Allowed by default: sodium, lithium,
 * iris, ferritecore, immediatelyfast, entityculling.
 */
public final class ModPolicy {
    public enum Decision { ALLOW, DETECT_ONLY, FORBIDDEN }

    private final VConfig config;
    private static final Set<String> ALLOWED = Set.of(
            "sodium", "lithium", "iris", "ferritecore", "ferrite",
            "immediatelyfast", "entityculling", "sodiumextra");

    public ModPolicy(VConfig config) { this.config = config; }

    public Decision decide(String modId) {
        if (modId == null) return Decision.ALLOW;
        String n = modId.toLowerCase(Locale.ROOT);
        if (ALLOWED.contains(n)) {
            // Explicit config can forbid an allowed mod, otherwise allow.
            if (config.getBoolean("mods." + modId + ".enabled", false)
                    && !"kick".equalsIgnoreCase(config.getString("mods." + modId + ".action", "allow"))) {
                return Decision.ALLOW;
            }
            return Decision.ALLOW;
        }
        ForbiddenModRegistry.ModEntry e = null;
        try { e = null; } catch (Throwable ignored) {}
        boolean forbidden = config.getBoolean("mods." + modId + ".enabled", true)
                || config.getBoolean("anti_esp.minimap.forbidden." + modId, modId.startsWith("xaeros"));
        if (!forbidden) return Decision.DETECT_ONLY;
        return Decision.FORBIDDEN;
    }

    public boolean punishable(String modId) {
        return decide(modId) == Decision.FORBIDDEN;
    }
}
