package com.hexa.vanticheat.platform;

/**
 * Platform abstraction (commercial §3-4). Only what genuinely differs is abstracted.
 * Folia is the native fast path; Paper uses the same engine with scheduler mapping
 * in VTaskManager. No GLOBAL scheduler invented.
 */
public enum ServerPlatform {
    FOLIA, PAPER, UNKNOWN;

    public static ServerPlatform detect() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return FOLIA;
        } catch (Throwable ignored) {}
        try {
            Class.forName("org.bukkit.entity.Player");
            return PAPER;
        } catch (Throwable ignored) {}
        return UNKNOWN;
    }

    public boolean isFolia() { return this == FOLIA; }
}
