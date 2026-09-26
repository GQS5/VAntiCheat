package site.vackstudio.vanticheat.detection.behavior.movement;

import site.vackstudio.vanticheat.detection.behavior.SignalLevel;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record FlyEvidence(String detector, long timestampNanos, UUID playerId,
                          Set<FlySignalType> signalTypes, long airborneDurationMillis,
                          double verticalDelta, double horizontalDelta,
                          FlyMovementState state, SignalLevel confidence,
                          Map<String, String> context) {
    public FlyEvidence {
        signalTypes = Set.copyOf(signalTypes);
        context = Map.copyOf(context);
    }
}
