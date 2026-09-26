package site.vackstudio.vanticheat.detection.behavior.movement;

import site.vackstudio.vanticheat.detection.behavior.SignalLevel;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record SpeedEvidence(String detector, long timestampNanos, UUID playerId,
                            Set<SpeedSignalType> signalTypes, double horizontalDistance,
                            double normalizedRate, double previousRate, double acceleration,
                            FlyMovementState movementState, boolean grounded,
                            long elapsedMillis, SignalLevel confidence,
                            Map<String, String> context) {
    public SpeedEvidence {
        signalTypes = Set.copyOf(signalTypes);
        context = Map.copyOf(context);
    }
}
