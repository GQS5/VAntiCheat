package site.vackstudio.vanticheat.detection.behavior.movement;

import site.vackstudio.vanticheat.detection.behavior.MovementObservation;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;

import java.util.Set;

public record FlyAnalysis(MovementObservation observation, Set<FlySignalType> signalTypes,
                          long airborneDurationMillis, FlyMovementState state,
                          SignalLevel confidence, int anomalyStreak) {
    public FlyAnalysis {
        signalTypes = Set.copyOf(signalTypes);
    }
}
