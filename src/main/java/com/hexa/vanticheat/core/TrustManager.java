package com.hexa.vanticheat.core;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trust system (spec §26): per-category bypass, never a master bypass.
 * Categories: client-security, movement, combat, xray, esp, interaction.
 */
public final class TrustManager {
    private final Set<UUID> trusted = ConcurrentHashMap.newKeySet();
    private final VAntiCheat plugin;

    public TrustManager(VAntiCheat plugin) { this.plugin = plugin; }

    public boolean isTrusted(UUID id) { return trusted.contains(id); }
    public void setTrusted(UUID id, boolean v) { if (v) trusted.add(id); else trusted.remove(id); }

    /** Category bypass only if trusted AND config trust.bypass.<cat> is true. */
    public boolean bypasses(UUID id, String category) {
        if (!trusted.contains(id)) return false;
        try {
            return plugin.vacConfig().getBoolean("trust.bypass." + category, false);
        } catch (Throwable t) { return false; }
    }
}
