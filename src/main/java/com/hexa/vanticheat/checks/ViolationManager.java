package com.hexa.vanticheat.checks;

import com.hexa.vanticheat.core.VAntiCheat;
import com.hexa.vanticheat.core.VConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VL accounting with decay. VL alone never bans; PunishmentManager gates by confidence.
 * Decay: -decayPerSecond every second (async maintenance calls decayAll).
 */
public final class ViolationManager {

    private final VAntiCheat plugin;
    private final VConfig config;
    private final Map<UUID, Map<String, Double>> vl = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDecay = new ConcurrentHashMap<>();

    public ViolationManager(VAntiCheat plugin, VConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public double add(UUID player, String checkId, double amount) {
        Map<String, Double> m = vl.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        return m.merge(checkId, amount, Double::sum);
    }

    public double get(UUID player, String checkId) {
        Map<String, Double> m = vl.get(player);
        return m == null ? 0.0 : m.getOrDefault(checkId, 0.0);
    }

    public void decayAll() {
        double perSec = config.getDouble("behavior.vl-decay-per-second", 0.05);
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Map<String, Double>> e : vl.entrySet()) {
            long last = lastDecay.getOrDefault(e.getKey(), now);
            double dt = Math.min(60.0, (now - last) / 1000.0);
            if (dt <= 0) continue;
            lastDecay.put(e.getKey(), now);
            double dec = perSec * dt;
            e.getValue().replaceAll((k, v) -> Math.max(0.0, v - dec));
        }
    }

    public void reset(UUID player) {
        vl.remove(player);
        lastDecay.remove(player);
    }
}
