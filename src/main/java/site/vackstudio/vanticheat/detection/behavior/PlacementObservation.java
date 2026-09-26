package site.vackstudio.vanticheat.detection.behavior;

import java.util.Objects;
import java.util.UUID;

public record PlacementObservation(UUID playerId, long timestampNanos,
                                   Position3d placedPosition, Position3d supportPosition,
                                   String face, String blockType, String heldItem,
                                   Position3d playerPosition, float yaw, float pitch,
                                   boolean grounded, boolean sneaking,
                                   MovementContext context, double distanceToPlacement)
        implements BehaviorObservation {
    public PlacementObservation {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(placedPosition, "placedPosition");
        Objects.requireNonNull(supportPosition, "supportPosition");
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(blockType, "blockType");
        Objects.requireNonNull(heldItem, "heldItem");
        Objects.requireNonNull(playerPosition, "playerPosition");
        Objects.requireNonNull(context, "context");
        if (Double.isNaN(distanceToPlacement) || distanceToPlacement < 0.0) {
            throw new IllegalArgumentException("distanceToPlacement must be non-negative");
        }
    }

    @Override public ObservationType type() { return ObservationType.PLACEMENT; }
}
