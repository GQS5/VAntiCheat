package com.hexa.vanticheat.checks.interaction;

import com.hexa.vanticheat.checks.CheckResult;
import com.hexa.vanticheat.core.VConfig;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FastPlace / FastBreak / impossible-interaction heuristics.
 * Sustained-rate based; single fast event never flags.
 */
public final class InteractionChecks {

    private final VConfig config;
    private final Map<UUID, Long> lastPlace = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> placeBurst = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBreak = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> breakBurst = new ConcurrentHashMap<>();

    public InteractionChecks(VConfig config) { this.config = config; }

    public CheckResult onPlace(Player p, BlockPlaceEvent e) {
        var snap = config.snapshot();
        if (!snap.fastplaceEnabled || !snap.behaviorEnabled) return CheckResult.pass();
        long now = System.currentTimeMillis();
        Long last = lastPlace.put(p.getUniqueId(), now);
        if (last == null) return CheckResult.pass();
        long dt = now - last;
        int burst = breakBurst0(placeBurst, p.getUniqueId(), dt < 120);
        if (burst >= 8) {
            placeBurst.put(p.getUniqueId(), 0);
            return CheckResult.fail(1.0, 8, "FastPlace", "place-rate", Map.of("burst", String.valueOf(burst)));
        }
        // Impossible: block placed >6m away (server-side reach for placement).
        try {
            double d = p.getEyeLocation().distance(e.getBlock().getLocation().add(0.5, 0.5, 0.5));
            if (d > 6.5) {
                return CheckResult.fail(1.0, 8, "ImpossibleInteraction", "place-distance", Map.of("dist", String.format("%.2f", d)));
            }
        } catch (Exception ignored) {
        }
        return CheckResult.pass();
    }

    public CheckResult onBreak(Player p, BlockBreakEvent e) {
        var snap = config.snapshot();
        if (!snap.fastbreakEnabled || !snap.behaviorEnabled) return CheckResult.pass();
        long now = System.currentTimeMillis();
        Long last = lastBreak.put(p.getUniqueId(), now);
        if (last == null) return CheckResult.pass();
        long dt = now - last;
        int burst = breakBurst0(breakBurst, p.getUniqueId(), dt < 120);
        if (burst >= 8) {
            breakBurst.put(p.getUniqueId(), 0);
            return CheckResult.fail(1.0, 8, "FastBreak", "break-rate", Map.of("burst", String.valueOf(burst)));
        }
        try {
            double d = p.getEyeLocation().distance(e.getBlock().getLocation().add(0.5, 0.5, 0.5));
            if (d > 7.0) {
                return CheckResult.fail(1.0, 8, "ImpossibleInteraction", "break-distance", Map.of("dist", String.format("%.2f", d)));
            }
        } catch (Exception ignored) {
        }
        return CheckResult.pass();
    }

    private static int breakBurst0(Map<UUID, Integer> m, UUID id, boolean fast) {
        int v = m.getOrDefault(id, 0);
        v = fast ? v + 1 : Math.max(0, v - 2);
        m.put(id, v);
        return v;
    }

    public void purge(UUID id) {
        lastPlace.remove(id); placeBurst.remove(id); lastBreak.remove(id); breakBurst.remove(id);
    }
}
