package com.hexa.vanticheat.simulation;

/** Per-player investigation depth (§18). Drives how much work the engine spends. */
public enum InvestigationState {
    NORMAL, WATCH, SUSPICIOUS, INVESTIGATING, HIGH_CONFIDENCE, CONFIRMED;

    public boolean collectsReplay() { return this != NORMAL; }
    public boolean allowsSimulation() {
        return this == SUSPICIOUS || this == INVESTIGATING || this == HIGH_CONFIDENCE;
    }
}
