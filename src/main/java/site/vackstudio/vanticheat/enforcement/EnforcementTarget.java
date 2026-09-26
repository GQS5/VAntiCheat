package site.vackstudio.vanticheat.enforcement;

import java.util.Objects;
import java.util.UUID;

public record EnforcementTarget(UUID id, String name, boolean online, Object platformHandle) {
    public EnforcementTarget {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }
}
