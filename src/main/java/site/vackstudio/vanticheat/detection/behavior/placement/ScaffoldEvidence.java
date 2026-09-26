package site.vackstudio.vanticheat.detection.behavior.placement;

import site.vackstudio.vanticheat.detection.behavior.SignalLevel;
import site.vackstudio.vanticheat.detection.behavior.movement.FlyMovementState;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ScaffoldEvidence(String detector, long timestampNanos, UUID playerId,
                               Set<ScaffoldSignalType> signalTypes, String placedPosition,
                               String supportPosition, String face, String playerPosition,
                               float yaw, float pitch, long placementIntervalMillis,
                               FlyMovementState movementState, boolean grounded, boolean sneaking,
                               int sequenceLength, double facingDot, SignalLevel confidence,
                               Map<String, String> context) {
    public ScaffoldEvidence {
        signalTypes = Set.copyOf(signalTypes);
        context = Map.copyOf(context);
    }
}
