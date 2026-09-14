package com.hexa.vanticheat.antixray;

import com.hexa.vanticheat.core.VConfig;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks per-player ore discoveries with exposure context (was the ore
 * visible from a cave/air pocket or fully buried?). Exposure is computed
 * from the 6 neighbors at break time — O(1), no chunk scans.
 */
public final class OreExposureTracker {

    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final VConfig config;
    private final OreProtection protection;
    private final Map<UUID, PlayerOreStats> stats = new ConcurrentHashMap<>();

    public OreExposureTracker(VConfig config) {
        this.config = config;
        this.protection = new OreProtection(config);
    }

    public record Discovery(UUID player, Material ore, boolean exposed, boolean rare, long at) {}

    /** Returns discovery or null when block is not a tracked valuable. Caller feeds MiningAnalyzer. */
    public Discovery track(UUID player, Block block) {
        Material type = block.getType();
        if (!protection.isValuable(type)) return null;
        boolean exposed = isExposed(block);
        Discovery d = new Discovery(player, type, exposed, protection.isRare(type), System.currentTimeMillis());
        PlayerOreStats s = stats.computeIfAbsent(player, k -> new PlayerOreStats());
        synchronized (s) {
            s.total++;
            if (exposed) s.exposed++; else s.hidden++;
            if (d.rare()) { s.rare++; s.lastRareAt = d.at(); }
        }
        return d;
    }

    /** Exposed = at least one neighboring face is air/cave-like (non-occluding). */
    public static boolean isExposed(Block block) {
        try {
            for (BlockFace f : FACES) {
                Material m = block.getRelative(f).getType();
                if (m.isAir() || m == Material.WATER || m == Material.LAVA || m == Material.CAVE_AIR || m == Material.VOID_AIR) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public PlayerOreStats snapshot(UUID player) {
        PlayerOreStats s = stats.get(player);
        if (s == null) return new PlayerOreStats();
        synchronized (s) { return s.copy(); }
    }

    public void purge(UUID player) { stats.remove(player); }

    public static final class PlayerOreStats {
        public long total, exposed, hidden, rare;
        public long lastRareAt;
        public double hiddenRatio() { return total == 0 ? 0 : (double) hidden / total; }
        public PlayerOreStats copy() {
            PlayerOreStats c = new PlayerOreStats();
            c.total = total; c.exposed = exposed; c.hidden = hidden; c.rare = rare; c.lastRareAt = lastRareAt;
            return c;
        }
    }
}
