package site.vackstudio.vanticheat.detection.behavior;

import java.util.Objects;

public record BehaviorDefinition(String id, String name, boolean enabled) {
    public BehaviorDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (id.isBlank() || name.isBlank()) throw new IllegalArgumentException("Behavior definition cannot be blank");
    }
}
