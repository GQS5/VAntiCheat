package site.vackstudio.vanticheat.detection.behavior;

import java.util.Objects;
import java.util.UUID;

public record BehaviorTarget(UUID id, String name) {
    public BehaviorTarget {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }
}
