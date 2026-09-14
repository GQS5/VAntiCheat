package com.hexa.vanticheat.simulation;

import com.hexa.vanticheat.state.NormalizedCombatState;

/**
 * Targeted combat geometry (§27). Pure math on normalized state; invoked only
 * for borderline/high-value hits, never every swing.
 */
public final class CombatGeometry {
    private CombatGeometry() {}

    /** Plausible max reach given context (hitbox ~0.6 + interp + lag slack). */
    public static double plausibleMax(NormalizedCombatState s, double baseMax) {
        double slack = s.severeLag() ? 0.6 : s.ping() > 150 ? 0.3 : 0.0;
        return baseMax + slack;
    }

    public static boolean impossible(NormalizedCombatState s, double baseMax, int repeatCount) {
        if (repeatCount < 3) return false; // one hit never proof
        return s.distance() > plausibleMax(s, baseMax);
    }
}
