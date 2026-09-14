package com.hexa.vanticheat.version;

/**
 * Server version profile (§5/§29-30). Compact constants, not duplicate engines.
 * Native fast path: 1.21.11. Others map to capability tiers after real testing.
 */
public final class ServerProfile {
    public enum Tier { NATIVE, SUPPORTED, COMPATIBILITY, UNSUPPORTED }

    private final String minecraftVersion;
    private final Tier tier;
    // Physics/protocol constants that genuinely differ across versions.
    private final double baseWalkSpeed;
    private final double baseReach;
    private final boolean hasSweepAttacks;

    public ServerProfile(String minecraftVersion, Tier tier, double baseWalkSpeed,
                         double baseReach, boolean hasSweepAttacks) {
        this.minecraftVersion = minecraftVersion;
        this.tier = tier;
        this.baseWalkSpeed = baseWalkSpeed;
        this.baseReach = baseReach;
        this.hasSweepAttacks = hasSweepAttacks;
    }

    public static ServerProfile native12111() {
        return new ServerProfile("1.21.11", Tier.NATIVE, 0.21585, 3.0, true);
    }

    public static ServerProfile detect() {
        try {
            String v = org.bukkit.Bukkit.getMinecraftVersion();
            if (v != null && v.startsWith("1.21")) return native12111();
            return new ServerProfile(v == null ? "unknown" : v, Tier.COMPATIBILITY, 0.21585, 3.0, true);
        } catch (Throwable t) {
            return native12111();
        }
    }

    public String minecraftVersion() { return minecraftVersion; }
    public Tier tier() { return tier; }
    public boolean nativeFastPath() { return tier == Tier.NATIVE; }
    public double baseWalkSpeed() { return baseWalkSpeed; }
    public double baseReach() { return baseReach; }
    public boolean hasSweepAttacks() { return hasSweepAttacks; }
}
