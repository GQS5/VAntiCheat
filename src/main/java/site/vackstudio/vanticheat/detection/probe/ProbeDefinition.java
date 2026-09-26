package site.vackstudio.vanticheat.detection.probe;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record ProbeDefinition(
        String id,
        String displayName,
        String key,
        ProbeMode mode,
        String fallback,
        boolean enabled,
        ProbeVerificationStatus verificationStatus) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    public ProbeDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(verificationStatus, "verificationStatus");
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid probe id");
        if (displayName.isBlank()) throw new IllegalArgumentException("Probe display name cannot be blank");
        if (key.isBlank()) throw new IllegalArgumentException("Probe key cannot be blank");
        if (fallback == null || fallback.isBlank()) {
            fallback = "\u27e6NO_" + id.toUpperCase(Locale.ROOT).replace('-', '_') + "\u27e7";
        }
    }
}
