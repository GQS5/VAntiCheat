package site.vackstudio.vanticheat.detection.behavior;

import java.util.Objects;
import java.util.UUID;

public record FallDamageObservation(UUID playerId, long timestampNanos, double finalDamage)
        implements BehaviorObservation {
    public FallDamageObservation {
        Objects.requireNonNull(playerId, "playerId");
        if (Double.isNaN(finalDamage) || finalDamage < 0.0) {
            throw new IllegalArgumentException("finalDamage must be non-negative");
        }
    }

    @Override public ObservationType type() { return ObservationType.IMPACT; }
}
