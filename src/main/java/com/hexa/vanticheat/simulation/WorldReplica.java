package com.hexa.vanticheat.simulation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compact world replica (§10): per-player recent relevant block changes only.
 * Lazy, demand-driven, region-aware (written on region thread), bounded 48.
 * Never a full world copy.
 */
public final class WorldReplica {
    public record Change(long at, int x, int y, int z, String type) {}
    private static final int MAX = 48;
    private final Map<UUID, Deque<Change>> data = new ConcurrentHashMap<>();

    public void observe(UUID player, int x, int y, int z, String type) {
        Deque<Change> q = data.computeIfAbsent(player, k -> new ArrayDeque<>(MAX));
        synchronized (q) {
            q.addLast(new Change(System.currentTimeMillis(), x, y, z, type));
            while (q.size() > MAX) q.pollFirst();
        }
    }

    public int recentCount(UUID player, long windowMs) {
        Deque<Change> q = data.get(player);
        if (q == null) return 0;
        long cutoff = System.currentTimeMillis() - windowMs;
        int n = 0;
        synchronized (q) {
            for (Change c : q) if (c.at() >= cutoff) n++;
        }
        return n;
    }

    public void purge(UUID player) { data.remove(player); }
}
