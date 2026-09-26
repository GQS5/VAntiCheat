package site.vackstudio.vanticheat.detection.behavior.movement;

import site.vackstudio.vanticheat.detection.behavior.SignalLevel;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record NoFallEvidence(String detector, long timestampNanos, UUID playerId,
                             Set<NoFallSignalType> signalTypes, long fallDurationMillis,
                             double estimatedFallDistance, double startY, double lowestY,
                             double landingY, NoFallStateKind state,
                             boolean expectedImpact, boolean observedImpact,
                             SignalLevel confidence, Map<String, String> context) {
    public NoFallEvidence {
        signalTypes = Set.copyOf(signalTypes);
        context = Map.copyOf(context);
    }
}
