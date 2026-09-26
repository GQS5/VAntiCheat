package site.vackstudio.vanticheat.detection;

import java.util.Objects;
import java.util.regex.Pattern;

public record DetectionDefinition(
        String id,
        String name,
        String description,
        DetectionCategory category,
        DetectionSeverity severity,
        boolean enabled) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    public DetectionDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid detection id");
        if (name.isBlank()) throw new IllegalArgumentException("Detection name cannot be blank");
    }
}
