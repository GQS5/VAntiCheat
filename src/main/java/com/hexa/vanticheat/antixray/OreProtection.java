package com.hexa.vanticheat.antixray;

import com.hexa.vanticheat.core.VConfig;
import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Set;

/** Configurable valuable-block set + exposure helpers. */
public final class OreProtection {

    private final VConfig config;
    private volatile Set<Material> valuables;

    public OreProtection(VConfig config) {
        this.config = config;
        reload();
    }

    public void reload() {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String name : config.getString("antixray.valuable-blocks",
                "DIAMOND_ORE,DEEPSLATE_DIAMOND_ORE,EMERALD_ORE,DEEPSLATE_EMERALD_ORE,ANCIENT_DEBRIS,GOLD_ORE,DEEPSLATE_GOLD_ORE,IRON_ORE,DEEPSLATE_IRON_ORE,REDSTONE_ORE,DEEPSLATE_REDSTONE_ORE,LAPIS_ORE,DEEPSLATE_LAPIS_ORE,NETHER_GOLD_ORE,NETHER_QUARTZ_ORE").split(",")) {
            try { set.add(Material.valueOf(name.trim().toUpperCase())); } catch (Exception ignored) {}
        }
        if (set.isEmpty()) set.add(Material.DIAMOND_ORE);
        valuables = Set.copyOf(set);
    }

    public boolean isValuable(Material m) { return valuables.contains(m); }
    public boolean isRare(Material m) {
        return m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE
                || m == Material.EMERALD_ORE || m == Material.DEEPSLATE_EMERALD_ORE
                || m == Material.ANCIENT_DEBRIS;
    }
}
