package site.vackstudio.vanticheat.detection.behavior.placement;

import site.vackstudio.vanticheat.detection.behavior.PlacementObservation;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;
import site.vackstudio.vanticheat.detection.behavior.movement.FlyMovementState;

import java.util.Set;

public record ScaffoldAnalysis(PlacementObservation observation, Set<ScaffoldSignalType> signalTypes,
                               long intervalMillis, double facingDot, boolean supportAdjacent,
                               boolean supportContinues, boolean moving, FlyMovementState movementState,
                               SignalLevel confidence, int sequenceLength) {
    public ScaffoldAnalysis {
        signalTypes = Set.copyOf(signalTypes);
    }
}
