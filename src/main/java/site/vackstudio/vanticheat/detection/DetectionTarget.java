package site.vackstudio.vanticheat.detection;

import java.util.Objects;
import java.util.UUID;

public record DetectionTarget(UUID id, String name, boolean online, Object platformHandle) {
    public DetectionTarget(UUID id, String name, boolean online) {
        this(id, name, online, null);
    }

    public DetectionTarget {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("Target name cannot be blank");
    }
}
