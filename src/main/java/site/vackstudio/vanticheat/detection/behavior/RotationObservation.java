package site.vackstudio.vanticheat.detection.behavior;

import java.util.UUID;

public record RotationObservation(UUID playerId, long timestampNanos, float yaw, float pitch,
                                  float yawDelta, float pitchDelta) implements BehaviorObservation {
    public RotationObservation {
        if (playerId == null) throw new NullPointerException("playerId");
    }

    @Override public ObservationType type() { return ObservationType.ROTATION; }
}
