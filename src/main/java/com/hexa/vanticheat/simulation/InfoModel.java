package com.hexa.vanticheat.simulation;

/**
 * Client-visible information model (§11): could the client legitimately have
 * known this? Server-observable only — no client surveillance claims.
 * Used by xray/esp to separate knowledge from luck.
 */
public final class InfoModel {
    private InfoModel() {}

    /** Ore was exposed to air at break time → legitimately visible. */
    public static boolean oreWasExposed(boolean exposed) { return exposed; }

    /** Hidden player with line-of-sight → visible, not ESP-relevant. */
    public static boolean targetWasVisible(boolean lineOfSight) { return lineOfSight; }

    /** Far hidden target repeatedly acquired → not explainable by luck. */
    public static boolean suspiciousAcquisition(boolean visible, double distance, int repeats) {
        return !visible && distance > 5 && distance < 50 && repeats >= 3;
    }
}
