package com.hexa.vanticheat.core;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * messages.yml CMS facade with safe defaults. Never throws; missing keys fall
 * back to built-in English. Templates are pre-parsed once (per reload) and
 * cached; rendering only happens for commands/alerts/kicks, never hot paths.
 */
public final class Messages {

    private final VAntiCheat plugin;
    private volatile FileConfiguration cfg = new YamlConfiguration();
    private volatile Map<String, MessageFormat.Template> cache = new ConcurrentHashMap<>();

    public Messages(VAntiCheat plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        try {
            File f = new File(plugin.getDataFolder(), "messages.yml");
            if (!f.exists()) {
                try { plugin.saveResource("messages.yml", false); } catch (Throwable ignored) {}
            }
            if (f.exists()) cfg = YamlConfiguration.loadConfiguration(f);
            cache = new ConcurrentHashMap<>(); // drop stale templates
        } catch (Throwable t) {
            plugin.getLogger().warning("[VAntiCheat] messages.yml unreadable, using defaults");
        }
    }

    public String get(String key, String def) {
        try {
            String s = cfg.getString(key);
            return s == null ? def : s;
        } catch (Throwable t) {
            return def;
        }
    }

    /** Rendered component with %name%/{name} placeholders (values stay plain). */
    public net.kyori.adventure.text.Component component(String key, String def, String... kv) {
        String raw = get(key, def);
        MessageFormat.Template t = cache.computeIfAbsent(key + "\0" + raw, k -> MessageFormat.parse(raw));
        java.util.HashMap<String, String> values = new java.util.HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) values.put(kv[i], kv[i + 1]);
        try {
            return t.render(values);
        } catch (Throwable th) {
            return net.kyori.adventure.text.Component.text(raw);
        }
    }

    /** Raw string with {name} substitution (for kick reasons / console logs). */
    public String raw(String key, String def, String... kv) {
        String s = get(key, def);
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s;
    }

    public String prefix() { return get("prefix", "[VAC] "); }
}
