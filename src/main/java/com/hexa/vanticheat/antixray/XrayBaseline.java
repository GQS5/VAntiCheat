package com.hexa.vanticheat.antixray;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Statistical baseline (spec §11): server + dimension + player history.
 * Tracks ore discovery rate; z-score = (playerRate - baselineMean) / sd.
 * Configurable via antixray.baseline.*. Bounded deques.
 */
public final class XrayBaseline {
    private final Deque<Double> serverRates = new ArrayDeque<>();
    private final Map<String, Deque<Double>> dimRates = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Double>> playerRates = new ConcurrentHashMap<>();
    private static final int MAX = 200;

    public synchronized void observeServer(double rate) {
        serverRates.addLast(rate);
        while (serverRates.size() > MAX) serverRates.pollFirst();
    }

    public void observe(UUID player, String dimension, double rate) {
        Deque<Double> d = dimRates.computeIfAbsent(dimension, k -> new ArrayDeque<>());
        synchronized (d) { d.addLast(rate); while (d.size() > MAX) d.pollFirst(); }
        Deque<Double> p = playerRates.computeIfAbsent(player, k -> new ArrayDeque<>());
        synchronized (p) { p.addLast(rate); while (p.size() > 40) p.pollFirst(); }
        observeServer(rate);
    }

    public double mean(Deque<Double> q) {
        if (q.isEmpty()) return 0;
        double s = 0; for (double v : q) s += v;
        return s / q.size();
    }

    public double sd(Deque<Double> q, double mean) {
        if (q.size() < 2) return 1.0;
        double s = 0; for (double v : q) s += (v - mean) * (v - mean);
        return Math.max(0.5, Math.sqrt(s / q.size()));
    }

    public synchronized double serverMean() { return mean(serverRates); }
    public synchronized double serverSd() { return sd(serverRates, serverMean()); }

    public double zScore(double rate) {
        double sd = serverSd();
        if (sd <= 0) return 0;
        return (rate - serverMean()) / sd;
    }

    public void purge(UUID player) { playerRates.remove(player); }
}
