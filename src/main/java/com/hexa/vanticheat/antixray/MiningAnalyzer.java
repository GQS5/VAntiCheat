package com.hexa.vanticheat.antixray;

import com.hexa.vanticheat.core.VConfig;
import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mining behavior analysis: tunnel directness, direction changes, rare-ore
 * spacing/timing. Bounded deques per player (no unbounded growth).
 */
public final class MiningAnalyzer {

    private static final int MAX_POINTS = 120;
    private final VConfig config;
    private final Map<UUID, Deque<MinePoint>> points = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> rareTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Location>> rareLocs = new ConcurrentHashMap<>();

    public record MinePoint(Location loc, boolean ore, boolean hiddenOre, long at) {}
    public record Analysis(double hiddenOreRatio, double rarePerHour, double avgRareSpacing,
                           int sharpTurns, int hiddenRareStreak, long sampleSize) {}

    public MiningAnalyzer(VConfig config) { this.config = config; }

    public void onBlockBreak(UUID player, Location loc, boolean wasOre, boolean hiddenOre) {
        Deque<MinePoint> q = points.computeIfAbsent(player, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(new MinePoint(loc.clone(), wasOre, hiddenOre, System.currentTimeMillis()));
            while (q.size() > MAX_POINTS) q.pollFirst();
        }
    }

    public void onRare(UUID player, Location loc) {
        long now = System.currentTimeMillis();
        Deque<Long> t = rareTimes.computeIfAbsent(player, k -> new ArrayDeque<>());
        Deque<Location> l = rareLocs.computeIfAbsent(player, k -> new ArrayDeque<>());
        synchronized (t) {
            t.addLast(now);
            while (t.size() > 40) t.pollFirst();
        }
        synchronized (l) {
            l.addLast(loc.clone());
            while (l.size() > 40) l.pollFirst();
        }
    }

    public Analysis analyze(UUID player) {
        Deque<MinePoint> q = points.get(player);
        if (q == null) return new Analysis(0, 0, -1, 0, 0, 0);
        MinePoint[] arr;
        synchronized (q) { arr = q.toArray(new MinePoint[0]); }
        if (arr.length < 10) return new Analysis(0, 0, -1, 0, 0, arr.length);
        long hiddenOre = 0, ores = 0, streak = 0, maxStreak = 0;
        for (MinePoint p : arr) {
            if (p.ore()) { ores++; if (p.hiddenOre()) { hiddenOre++; streak++; maxStreak = Math.max(maxStreak, streak); } else streak = 0; }
        }
        double hiddenRatio = ores == 0 ? 0 : (double) hiddenOre / ores;
        // Rare rate + spacing.
        Deque<Long> t = rareTimes.get(player);
        Deque<Location> l = rareLocs.get(player);
        double perHour = 0, spacing = -1;
        if (t != null && l != null) {
            Long[] ta; Location[] la;
            synchronized (t) { ta = t.toArray(new Long[0]); }
            synchronized (l) { la = l.toArray(new Location[0]); }
            if (ta.length >= 2) {
                long span = Math.max(1, ta[ta.length - 1] - ta[0]);
                perHour = ta.length * 3_600_000.0 / span;
            }
            if (la.length >= 2) {
                double sum = 0; int n = 0;
                for (int i = 1; i < la.length; i++) {
                    try {
                        if (la[i].getWorld().equals(la[i - 1].getWorld())) { sum += la[i].distance(la[i - 1]); n++; }
                    } catch (Exception ignored) {
                    }
                }
                if (n > 0) spacing = sum / n;
            }
        }
        // Sharp turns in recent path (tunneling directly between ores looks like long straight runs + sharp pivots).
        int turns = 0;
        for (int i = 2; i < arr.length; i++) {
            double ax = arr[i-1].loc().getX() - arr[i-2].loc().getX();
            double az = arr[i-1].loc().getZ() - arr[i-2].loc().getZ();
            double bx = arr[i].loc().getX() - arr[i-1].loc().getX();
            double bz = arr[i].loc().getZ() - arr[i-1].loc().getZ();
            double la2 = Math.hypot(ax, az), lb2 = Math.hypot(bx, bz);
            if (la2 < 0.5 || lb2 < 0.5) continue;
            double dot = (ax * bx + az * bz) / (la2 * lb2);
            if (dot < 0.2) turns++;
        }
        return new Analysis(hiddenRatio, perHour, spacing, turns, (int) maxStreak, arr.length);
    }

    public void decay() {
        // Drop stale points older than 30 min to bound memory and adapt to play sessions.
        long cutoff = System.currentTimeMillis() - 30 * 60_000L;
        for (Deque<MinePoint> q : points.values()) {
            synchronized (q) { while (!q.isEmpty() && q.peekFirst().at() < cutoff) q.pollFirst(); }
        }
    }

    public void purge(UUID player) {
        points.remove(player); rareTimes.remove(player); rareLocs.remove(player);
    }
}
