package site.vackstudio.vanticheat.detection.behavior.movement;

import site.vackstudio.vanticheat.detection.behavior.MovementObservation;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;

import java.util.Set;

public record SpeedAnalysis(MovementObservation observation, Set<SpeedSignalType> signalTypes,
                            double normalizedRate, double previousRate, double acceleration,
                            FlyMovementState movementState, SignalLevel confidence,
                            int elevatedStreak) {
    public SpeedAnalysis {
        signalTypes = Set.copyOf(signalTypes);
    }
}
