package com.hexa.vanticheat.client;

import com.hexa.vanticheat.core.VConfig;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Forbidden-mod registry: normalized ids + aliases. Never matches on a bare
 * vague token like "xaero" alone: every entry requires a full normalized
 * identifier or a known channel.
 */
public final class ForbiddenModRegistry {

    public record ModEntry(String id, String display, boolean enabled, String action, Set<String> identifiers) {}

    private final VConfig config;
    private volatile Map<String, ModEntry> byId = Map.of();
    private volatile Map<String, ModEntry> byIdentifier = Map.of();

    public ForbiddenModRegistry(VConfig config) {
        this.config = config;
        reload();
    }

    public void reload() {
        Map<String, ModEntry> ids = new HashMap<>();
        Map<String, ModEntry> ident = new HashMap<>();
        ConfigurationSection mods = config.section("mods");
        if (mods != null) {
            for (String key : mods.getKeys(false)) {
                ConfigurationSection s = mods.getConfigurationSection(key);
                if (s == null) continue;
                boolean enabled = s.getBoolean("enabled", true);
                String action = s.getString("action", "kick");
                String display = s.getString("display", key);
                List<String> raw = s.getStringList("identifiers");
                Set<String> norm = new HashSet<>();
                for (String r : raw) norm.add(normalize(r));
                norm.add(normalize(key));
                ModEntry e = new ModEntry(key, display, enabled, action, Set.copyOf(norm));
                ids.put(key, e);
                for (String n : norm) ident.put(n, e);
            }
        }
        // Built-in defaults if config omitted them.
        defaults(ids, ident);
        this.byId = Map.copyOf(ids);
        this.byIdentifier = Map.copyOf(ident);
    }

    private static void defaults(Map<String, ModEntry> ids, Map<String, ModEntry> ident) {
        add(ids, ident, "xaeros_minimap", "Xaero's Minimap", List.of("xaerominimap", "xaero_minimap", "xaero-minimap", "xaeros_minimap", "xaerominimap_fair"));
        add(ids, ident, "xaeros_worldmap", "Xaero's World Map", List.of("xaeroworldmap", "xaero_worldmap", "xaero-worldmap", "xaeros_worldmap"));
        add(ids, ident, "journeymap", "JourneyMap", List.of("journeymap"));
        add(ids, ident, "voxelmap", "VoxelMap", List.of("voxelmap"));
        add(ids, ident, "freecam", "Freecam", List.of("freecam"));
        add(ids, ident, "baritone", "Baritone", List.of("baritone"));
        add(ids, ident, "litematica", "Litematica", List.of("litematica"));
        add(ids, ident, "tweakeroo", "Tweakeroo", List.of("tweakeroo"));
        add(ids, ident, "xray", "X-Ray mod", List.of("xray", "x-ray", "advancedxray"));
    }

    private static void add(Map<String, ModEntry> ids, Map<String, ModEntry> ident,
                            String id, String display, List<String> identifiers) {
        if (ids.containsKey(id)) return;
        Set<String> norm = new HashSet<>();
        for (String r : identifiers) norm.add(normalize(r));
        norm.add(normalize(id));
        ModEntry e = new ModEntry(id, display, true, "kick", Set.copyOf(norm));
        ids.put(id, e);
        for (String n : norm) ident.putIfAbsent(n, e);
    }

    /** Normalize: lowercase, strip non-alphanumeric. "Xaero-Minimap" -> "xaerominimap". */
    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') sb.append((char) (c + 32));
            else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString();
    }

    /** Exact normalized match only. Returns null when no full-identifier match. */
    public ModEntry matchIdentifier(String raw) {
        String n = normalize(raw);
        if (n.isEmpty()) return null;
        return byIdentifier.get(n);
    }

    public List<ModEntry> all() { return new ArrayList<>(byId.values()); }
    public ModEntry byId(String id) { return byId.get(id); }
}
