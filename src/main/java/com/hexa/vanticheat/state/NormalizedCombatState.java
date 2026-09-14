package com.hexa.vanticheat.state;

/** Compact immutable combat view (§8). Primitives only. */
public record NormalizedCombatState(double distance, int ping, long dtMs,
                                    boolean multiTarget, boolean severeLag) {}
