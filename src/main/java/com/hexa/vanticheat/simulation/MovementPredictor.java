package com.hexa.vanticheat.simulation;

import com.hexa.vanticheat.state.NormalizedMovementState;
import com.hexa.vanticheat.version.ServerProfile;

/**
 * Cheap movement prediction (§28): predict legitimate displacement, compare
 * observed, return prediction error. Hot path does O(1) math only; called
 * after counters trip (triggered), never per-event for full analysis.
 */
public final class MovementPredictor {
    private MovementPredictor() {}

    /** Max plausible horizontal displacement per move event (native fast path). */
    public static double predictMax(NormalizedMovementState s, ServerProfile profile) {
        double base = s.sprinting() ? 0.75 : 0.45;
        if (!profile.nativeFastPath()) base += 0.1; // compat slack, no engine fork
        if (s.ping() > 250) base += 0.25;
        else if (s.ping() > 150) base += 0.12;
        return base;
    }

    /** Positive error = observed beyond plausible. Zero/negative = legitimate. */
    public static double error(NormalizedMovementState s, ServerProfile profile) {
        return s.horiz() - predictMax(s, profile);
    }
}
