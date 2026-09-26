package site.vackstudio.vanticheat.detection.behavior;

import java.util.Objects;
import java.util.UUID;

public record MovementObservation(UUID playerId, long timestampNanos, Position3d from, Position3d to,
                                  double deltaX, double deltaY, double deltaZ,
                                  double horizontalDistance, double verticalDistance,
                                  long durationMillis, boolean grounded,
                                  boolean onGroundTransition, Position3d velocity,
                                  MovementContext context) implements BehaviorObservation {
    public MovementObservation(UUID playerId, long timestampNanos, Position3d from, Position3d to,
                               double deltaX, double deltaY, double deltaZ,
                               double horizontalDistance, double verticalDistance,
                               long durationMillis, boolean grounded,
                               boolean onGroundTransition, Position3d velocity) {
        this(playerId, timestampNanos, from, to, deltaX, deltaY, deltaZ, horizontalDistance,
                verticalDistance, durationMillis, grounded, onGroundTransition, velocity,
                MovementContext.normal());
    }

    public MovementObservation {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(velocity, "velocity");
        Objects.requireNonNull(context, "context");
        if (durationMillis < 0) throw new IllegalArgumentException("durationMillis cannot be negative");
    }

    @Override public ObservationType type() { return ObservationType.MOVEMENT; }
}
