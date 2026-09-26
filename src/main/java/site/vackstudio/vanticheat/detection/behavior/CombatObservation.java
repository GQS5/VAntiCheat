package site.vackstudio.vanticheat.detection.behavior;

import java.util.Objects;
import java.util.UUID;

public record CombatObservation(UUID playerId, long timestampNanos, UUID targetId,
                                Position3d attackerPosition, Position3d attackerEyePosition,
                                Position3d targetPosition, BoundingBox3d targetBounds,
                                double distanceToTargetBounds, long attackIntervalMillis,
                                boolean lineOfSight, boolean uncertain, float attackStrength,
                                boolean attackerMoving, boolean targetMoving,
                                boolean knockbackContext, boolean nearbyTargets) implements BehaviorObservation {
    public CombatObservation(UUID playerId, long timestampNanos, UUID targetId,
                             Position3d attackerPosition, Position3d attackerEyePosition,
                             Position3d targetPosition, BoundingBox3d targetBounds,
                             double distanceToTargetBounds, long attackIntervalMillis,
                             boolean lineOfSight, boolean uncertain) {
        this(playerId, timestampNanos, targetId, attackerPosition, attackerEyePosition, targetPosition,
                targetBounds, distanceToTargetBounds, attackIntervalMillis, lineOfSight, uncertain,
                1.0f, false, false, false, false);
    }

    public CombatObservation {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(attackerPosition, "attackerPosition");
        Objects.requireNonNull(attackerEyePosition, "attackerEyePosition");
        Objects.requireNonNull(targetPosition, "targetPosition");
        Objects.requireNonNull(targetBounds, "targetBounds");
        if (attackIntervalMillis < 0) throw new IllegalArgumentException("attackIntervalMillis cannot be negative");
        if (Float.isNaN(attackStrength) || attackStrength < 0.0f) {
            throw new IllegalArgumentException("attackStrength must be non-negative");
        }
    }

    @Override public ObservationType type() { return ObservationType.COMBAT; }
}
