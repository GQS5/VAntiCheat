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
        boolean manual,
        boolean automatic,
        ProbeVerificationStatus verificationStatus,
        String category,
        String source,
        String notes,
        String expectedResponse) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    public ProbeDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(verificationStatus, "verificationStatus");
        category = category == null || category.isBlank() ? "general" : category;
        source = source == null ? "" : source;
        notes = notes == null ? "" : notes;
        expectedResponse = expectedResponse == null ? "" : expectedResponse;
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid probe id");
        if (displayName.isBlank()) throw new IllegalArgumentException("Probe display name cannot be blank");
        if (key.isBlank()) throw new IllegalArgumentException("Probe key cannot be blank");
        if (mode == ProbeMode.TRANSLATE && key.isBlank()) {
            throw new IllegalArgumentException("TRANSLATE probe key cannot be blank");
        }
        if (fallback == null || fallback.isBlank()) {
            fallback = "\u27e6NO_" + id.toUpperCase(Locale.ROOT).replace('-', '_') + "\u27e7";
        }
    }

    /** Compatibility constructor for pre-P15 callers and configurations. */
    public ProbeDefinition(String id, String displayName, String key, ProbeMode mode,
                           String fallback, boolean enabled, ProbeVerificationStatus verificationStatus) {
        this(id, displayName, key, mode, fallback, enabled, enabled, enabled, verificationStatus,
                "general", "", "", "");
    }

    public ProbeDefinition(String id, String displayName, String key, ProbeMode mode, String fallback,
                           boolean enabled, boolean manual, boolean automatic,
                           ProbeVerificationStatus verificationStatus) {
        this(id, displayName, key, mode, fallback, enabled, manual, automatic, verificationStatus,
                "general", "", "", "");
    }

    public ProbeDefinition(String id, String displayName, String key, ProbeMode mode, String fallback,
                           boolean enabled, boolean manual, boolean automatic,
                           ProbeVerificationStatus verificationStatus, String category,
                           String source, String notes) {
        this(id, displayName, key, mode, fallback, enabled, manual, automatic, verificationStatus,
                category, source, notes, "");
    }
}
