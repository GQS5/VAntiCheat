package com.hexa.vanticheat.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Typed config facade. All lookups go through here so reloads are safe
 * and defaults are centralized.
 */
public final class VConfig {

    private final VAntiCheat plugin;
    private final VLogger log;

    public VConfig(VAntiCheat plugin, VLogger log) {
        this.plugin = plugin;
        this.log = log;
    }

    private volatile ConfigSnapshot snapshot = ConfigSnapshot.defaults();
    private volatile long exemptCacheAt;
    private volatile boolean exemptOpsCached = true;

    public void reload() {
        plugin.reloadConfig();
        rebuildSnapshot();
    }

    /** Hot-path snapshot: plain-field reads, no YAML. Rebuilt on reload only. */
    public ConfigSnapshot snapshot() { return snapshot; }

    public void rebuildSnapshot() {
        try {
            snapshot = new ConfigSnapshot(
                    getBoolean("behavior.enabled", true),
                    getBoolean("behavior.checks.reach.enabled", true),
                    getBoolean("behavior.checks.killaura.enabled", true),
                    getBoolean("behavior.checks.fly.enabled", true),
                    getBoolean("behavior.checks.speed.enabled", true),
                    getBoolean("behavior.checks.nofall.enabled", true),
                    getBoolean("behavior.checks.step.enabled", true),
                    getBoolean("behavior.checks.fastplace.enabled", true),
                    getBoolean("behavior.checks.fastbreak.enabled", true),
                    getBoolean("antixray.enabled", true),
                    getBoolean("anti_esp.enabled", true) || getBoolean("antiesp.enabled", true),
                    getBoolean("client_security.enabled", true),
                    getBoolean("modlist.enabled", true),
                    getBoolean("antispoof.enabled", true),
                    getBoolean("punishment.enabled", true),
                    getBoolean("alerts.enabled", true),
                    getDouble("behavior.checks.reach.max-reach", 3.4),
                    getDouble("antixray.thresholds.hidden-ratio", 0.75),
                    getDouble("antixray.thresholds.rare-per-hour", 12.0),
                    getInt("antixray.thresholds.hidden-streak", 4),
                    getDouble("antixray.thresholds.min-avg-spacing", 25.0),
                    getInt("antixray.min-sample", 40),
                    getInt("anti_esp.history_seconds", 8),
                    profile(),
                    getBoolean("punishment.require-independent-signals", true),
                    getInt("punishment.minimum-independent-signals", 2),
                    getInt("performance.max-evidence-per-player", 200),
                    getBoolean("performance.profiling", false),
                    getInt("performance.profiling-sample-rate", 20));
        } catch (Throwable t) { snapshot = ConfigSnapshot.defaults(); }
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public boolean getBoolean(String path, boolean def) {
        return cfg().getBoolean(path, def);
    }

    public int getInt(String path, int def) {
        return cfg().getInt(path, def);
    }

    public long getLong(String path, long def) {
        return cfg().getLong(path, def);
    }

    public double getDouble(String path, double def) {
        return cfg().getDouble(path, def);
    }

    public String getString(String path, String def) {
        String s = cfg().getString(path);
        return s == null ? def : s;
    }

    public ConfigurationSection section(String path) {
        return cfg().getConfigurationSection(path);
    }

    public boolean debug() {
        return getBoolean("general.debug", false);
    }

    public SecurityProfile profile() {
        return SecurityProfile.parse(getString("security.profile", "standard"));
    }

    /** Admin-friendly validation: plain-language warnings with fallbacks. Never throws. */
    public java.util.List<String> validate(com.hexa.vanticheat.core.VLogger vlog) {
        java.util.List<String> warns = new java.util.ArrayList<>();
        String prof = getString("security.profile", "standard");
        if (!prof.matches("(?i)lenient|standard|hardcore|paranoid")) {
            warns.add("Setting: security.profile | Invalid value: '" + prof
                    + "' | Using: 'standard' (valid: lenient, standard, hardcore, paranoid)");
        }
        checkRange(warns, "confidence.decay.weak", 0, 100, 20);
        checkRange(warns, "confidence.decay.medium", 0, 100, 10);
        checkRange(warns, "confidence.decay.strong", 0, 100, 3);
        checkRange(warns, "confidence.decay.confirmed", 0, 100, 0);
        checkRange(warns, "punishment.minimum-independent-signals", 1, 5, 2);
        checkRange(warns, "alerts.min-confidence", 0, 100, 30);
        if (!warns.isEmpty()) {
            vlog.warn("VAntiCheat > Configuration warning — please review config.yml:");
            for (String s : warns) vlog.warn("  " + s);
        }
        return warns;
    }

    private void checkRange(java.util.List<String> warns, String key, int min, int max, int fallback) {
        int v = getInt(key, fallback);
        if (v < min || v > max) warns.add("Setting: " + key + " | Invalid value: " + v + " | Using: " + fallback);
    }

    private final java.util.Map<java.util.UUID, Long> exemptUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<java.util.UUID> exemptSet = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public boolean exempt(org.bukkit.entity.Player p) {
        if (p == null) return false;
        java.util.UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long until = exemptUntil.get(id);
        // 5s negative/positive cache: avoids permission lookups on every move packet.
        if (until != null && now < until) return exemptSet.contains(id);
        boolean v = computeExempt(p);
        exemptUntil.put(id, now + 5000);
        if (v) exemptSet.add(id); else exemptSet.remove(id);
        return v;
    }

    private boolean computeExempt(org.bukkit.entity.Player p) {
        try {
            if (p.hasPermission(VPermission.BYPASS) || p.hasPermission(VPermission.ADMIN)) return true;
        } catch (Throwable ignored) {}
        if (p.isOp() && getBoolean("general.exempt-ops", true)) return true;
        try { return api() != null && api().isTrusted(p.getUniqueId()); } catch (Throwable ignored) { return false; }
    }

    public void invalidateExempt(java.util.UUID id) { exemptUntil.remove(id); exemptSet.remove(id); }

    private com.hexa.vanticheat.api.VAntiCheatAPI api() {
        return VAntiCheat.api();
    }
}
