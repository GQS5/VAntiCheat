package site.vackstudio.vanticheat.detection.behavior;

import java.util.Objects;
import java.util.UUID;

public record InteractionObservation(UUID playerId, long timestampNanos, String interaction,
                                    String blockType, Position3d blockPosition, String heldItem) implements BehaviorObservation {
    public InteractionObservation {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(interaction, "interaction");
        Objects.requireNonNull(blockType, "blockType");
        Objects.requireNonNull(blockPosition, "blockPosition");
        Objects.requireNonNull(heldItem, "heldItem");
    }

    @Override public ObservationType type() { return ObservationType.INTERACTION; }
}
