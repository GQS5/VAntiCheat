package site.vackstudio.vanticheat.detection.behavior.combat;

import java.util.Set;
import java.util.UUID;

public record AttackSequence(long index, long timestampNanos, UUID targetId, long intervalMillis,
                             double rotationDelta, Set<CombatSignalType> signals) {
    public AttackSequence {
        signals = Set.copyOf(signals);
    }
}
