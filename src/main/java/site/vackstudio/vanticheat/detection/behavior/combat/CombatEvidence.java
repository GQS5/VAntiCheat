package site.vackstudio.vanticheat.detection.behavior.combat;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record CombatEvidence(String detector, long timestampNanos, UUID attackerId, UUID targetId,
                             long attackIntervalMillis, double measuredDistance, double rotationDelta,
                             long sequenceIndex, Set<CombatSignalType> signalTypes,
                             CombatConfidence confidence, Map<String, String> context) {
    public CombatEvidence {
        signalTypes = Set.copyOf(signalTypes);
        context = Map.copyOf(context);
    }
}
