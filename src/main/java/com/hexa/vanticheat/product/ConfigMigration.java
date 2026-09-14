package com.hexa.vanticheat.product;

/**
 * Config migration (§34): versioned, non-destructive. Evidence, trust,
 * policies and custom fingerprints survive updates; missing keys gain defaults.
 */
public final class ConfigMigration {
    public static final int CURRENT = 4; // 1.0→1, 1.1→2, 1.2/product→3, 1.3/qa+ux→4

    private ConfigMigration() {}

    /** Returns migration notes; never deletes user data. */
    public static java.util.List<String> migrate(com.hexa.vanticheat.core.VAntiCheat plugin) {
        java.util.List<String> notes = new java.util.ArrayList<>();
        int from = 1;
        try {
            var cfg = plugin.getConfig();
            from = cfg.getInt("config-version", 1);
            if (from >= CURRENT) return notes; // nothing to do, stay quiet
            if (from < 2) {
                cfg.set("security.profile", cfg.getString("security.profile", "hardcore"));
                notes.add("security profile defaults preserved");
            }
            if (from < 3) {
                ensure(cfg, "features.core", true);
                ensure(cfg, "features.client-security", true);
                ensure(cfg, "features.behavior", true);
                ensure(cfg, "features.anti-xray", true);
                ensure(cfg, "features.anti-esp", true);
                ensure(cfg, "features.advanced-simulation", true);
                ensure(cfg, "performance.profiling", false);
                notes.add("feature flags added (all enabled, custom policy kept)");
            }
            if (from < 4) {
                ensure(cfg, "qa.punishment.dry-run", false);
                notes.add("QA dry-run option added (off, your policy untouched)");
            }
            cfg.set("config-version", CURRENT);
            plugin.saveConfig();
            notes.add(0, "Configuration migrated: previous v" + from + " → current v" + CURRENT
                    + " | Preserved: policies, trust, evidence");
        } catch (Throwable t) {
            notes.add("migration deferred: " + t.getMessage());
        }
        return notes;
    }

    private static void ensure(org.bukkit.configuration.file.FileConfiguration cfg, String k, Object def) {
        if (!cfg.isSet(k)) cfg.set(k, def);
    }
}
