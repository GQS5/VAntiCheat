package com.hexa.vanticheat.antiesp;

import com.hexa.vanticheat.core.VConfig;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects tracking of hidden players: sustained yaw correlation toward a
 * player that is NOT visible (behind walls / far underground / vanished).
 * Requires repeated correlations; a single look is never evidence.
 */
public final class PlayerTrackingAnalyzer {

    private static final int MAX = 60;
    private final VConfig config;
    private final Map<UUID, Deque<Sample>> samples = new ConcurrentHashMap<>();

    record Sample(float yaw, float pitch, long at) {}
    public record Result(boolean suspicious, String reason, int weight, Map<String, String> values) {}

    public PlayerTrackingAnalyzer(VConfig config) { this.config = config; }

    public void sample(Player p, Location to) {
        Deque<Sample> q = samples.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(new Sample(to.getYaw(), to.getPitch(), System.currentTimeMillis()));
            while (q.size() > MAX) q.pollFirst();
        }
    }

    public Result evaluate(Player p) {
        Map<String, String> empty = Map.of();
        int historySec = 8;
        try { historySec = config.getInt("anti_esp.history_seconds", 8); } catch (Throwable ignored) {}
        // Find nearest hidden player (different Y level underground or behind wall via line-of-sight).
        Player target = null;
        double best = Double.MAX_VALUE;
        try {
            for (Player o : p.getWorld().getPlayers()) {
                if (o.equals(p) || o.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
                double d = p.getLocation().distanceSquared(o.getLocation());
                if (d < 25 || d > 2500) continue; // 5..50 blocks
                if (p.hasLineOfSight(o)) continue; // visible => not ESP-relevant
                if (d < best) { best = d; target = o; }
            }
        } catch (Exception e) {
            return new Result(false, "", 0, empty);
        }
        if (target == null) return new Result(false, "", 0, empty);
        // Yaw toward target vs actual yaw, over recent samples.
        Deque<Sample> q = samples.get(p.getUniqueId());
        if (q == null) return new Result(false, "", 0, empty);
        Sample[] arr;
        // Rolling window (§12): only samples within history_seconds.
        long cutoff = System.currentTimeMillis() - historySec * 1000L;
        synchronized (q) { arr = q.stream().filter(s -> s.at() >= cutoff).toArray(Sample[]::new); }
        if (arr.length < 20) return new Result(false, "", 0, empty);
        int aligned = 0;
        Location pl = p.getLocation();
        double wantYaw = Math.toDegrees(Math.atan2(
                -(target.getLocation().getX() - pl.getX()),
                (target.getLocation().getZ() - pl.getZ())));
        for (Sample s : arr) {
            double diff = Math.abs(wrap(s.yaw() - wantYaw));
            if (diff < 12) aligned++;
        }
        double ratio = (double) aligned / arr.length;
        if (ratio > 0.8 && arr.length >= 30) {
            Map<String, String> vals = new HashMap<>();
            vals.put("aligned", aligned + "/" + arr.length);
            vals.put("dist", String.format("%.1f", Math.sqrt(best)));
            return new Result(true, "Sustained tracking of hidden player " + target.getName(), 15, vals);
        }
        return new Result(false, "", 0, empty);
    }

    private static double wrap(double d) {
        while (d > 180) d -= 360;
        while (d < -180) d += 360;
        return d;
    }

    public void decay() {
        long cutoff = System.currentTimeMillis() - 60_000L;
        for (Deque<Sample> q : samples.values()) {
            synchronized (q) { while (!q.isEmpty() && q.peekFirst().at() < cutoff) q.pollFirst(); }
        }
    }

    public void purge(UUID id) { samples.remove(id); }
}
