package site.vackstudio.vanticheat.detection.behavior.movement;

import site.vackstudio.vanticheat.detection.behavior.SignalLevel;

import java.util.Set;
import java.util.UUID;

public record NoFallAnalysis(UUID playerId, long timestampNanos, Set<NoFallSignalType> signalTypes,
                             long fallDurationMillis, double estimatedFallDistance,
                             double startY, double lowestY, double landingY,
                             NoFallStateKind state, boolean expectedImpact,
                             boolean observedImpact, SignalLevel confidence,
                             int mismatchStreak) {
    public NoFallAnalysis {
        signalTypes = Set.copyOf(signalTypes);
    }
}
