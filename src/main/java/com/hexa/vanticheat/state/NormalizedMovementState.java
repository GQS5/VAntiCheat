package com.hexa.vanticheat.state;

/** Compact immutable movement view for analysis (§8). No Bukkit leakage. */
public record NormalizedMovementState(double dx, double dy, double dz, double horiz,
                                      boolean onGround, boolean sprinting, int ping) {
    public static NormalizedMovementState of(double dx, double dy, double dz,
                                             boolean onGround, boolean sprinting, int ping) {
        return new NormalizedMovementState(dx, dy, dz, Math.sqrt(dx * dx + dz * dz), onGround, sprinting, ping);
    }
}
