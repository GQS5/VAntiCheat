package com.hexa.vanticheat.core;

/**
 * Cached server-tick health (1.2 §15). TPS/MSPT sampled on ASYNC timer 1/s;
 * hot path reads volatile fields — never calls Bukkit.getTPS() per event.
 * Owner: async sampler; readers: REGION/ENTITY (lock-free).
 */
public final class TpsMeter {
    private volatile double tps = 20.0;
    private volatile double mspt = 10.0;
    private volatile long lastSampleAt = System.currentTimeMillis();

    public void sample() {
        try {
            double[] t = org.bukkit.Bukkit.getTPS();
            if (t != null && t.length > 0 && t[0] > 0 && t[0] <= 21) tps = t[0];
            lastSampleAt = System.currentTimeMillis();
        } catch (Throwable ignored) {}
    }

    public double tps() { return tps; }
    public boolean degraded() { return tps < 17.0; }
}
