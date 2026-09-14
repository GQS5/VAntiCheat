package com.hexa.vanticheat.core;

import org.bukkit.entity.Player;

/**
 * Centralized lag compensation (1.2 §15). Cheap: ping read + cached TPS.
 * No Bukkit.getTPS() per event (uses TpsMeter sampled 1/s on ASYNC).
 * No Vector allocation unless velocity explicitly needed.
 */
public final class LagContext {
    public final int ping;
    public final boolean severeLag;
    public final boolean degradedTick;
    public final double velocitySq; // -1 when not sampled

    public LagContext(int ping, boolean severeLag, boolean degradedTick, double velocitySq) {
        this.ping = ping;
        this.severeLag = severeLag;
        this.degradedTick = degradedTick;
        this.velocitySq = velocitySq;
    }

    /** Hot path: ping + cached TPS only. Set sampleVelocity=true only when anomaly suspected. */
    public static LagContext of(Player p, TpsMeter meter, boolean sampleVelocity) {
        int ping = 0;
        try { ping = p.getPing(); } catch (Throwable ignored) {}
        boolean degraded = meter != null && meter.degraded();
        boolean severe = ping > 300 || degraded;
        double vel = -1;
        if (sampleVelocity) {
            try { vel = p.getVelocity().lengthSquared(); } catch (Throwable ignored) {}
        }
        return new LagContext(ping, severe, degraded, vel);
    }

    /** Legacy compat: no meter (assumes healthy tick). */
    public static LagContext of(Player p) {
        return of(p, null, false);
    }

    /** Confidence penalty during lag: 0 normal, up to -15 severe. */
    public int penalty() {
        if (severeLag) return 15;
        if (ping > 200) return 8;
        if (ping > 120) return 3;
        return 0;
    }

    public double slack() {
        if (ping > 250) return 0.6;
        if (ping > 150) return 0.3;
        return 0.0;
    }

    // Legacy field compat
    public boolean recentTeleport() { return false; }
    public boolean recentVelocity() { return velocitySq > 1.0; }
}
