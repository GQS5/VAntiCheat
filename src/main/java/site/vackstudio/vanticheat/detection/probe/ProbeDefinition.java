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
        String expectedResponse,
        ProbeTransportMode transport,
        boolean identityResolution,
        String passiveChannel) {
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
        if (transport == null) {
            // Fail closed toward gameplay safety: an undeclared transport is assumed to
            // require client UI, so it can never silently join the automatic path.
            transport = ProbeTransportMode.INTERACTIVE;
        }
        if (passiveChannel != null) passiveChannel = passiveChannel.strip();
        if (transport == ProbeTransportMode.PASSIVE && (passiveChannel == null || passiveChannel.isBlank())) {
            throw new IllegalArgumentException("Passive probe must declare a passive channel: " + id);
        }
        if (transport == ProbeTransportMode.INTERACTIVE && passiveChannel != null && !passiveChannel.isBlank()) {
            throw new IllegalArgumentException("Interactive probe cannot declare a passive channel: " + id);
        }
        // A PASSIVE probe is served from an already-available signal, so it can never need
        // the interactive sign round-trip. Keep the two declarations consistent.
        if (transport == ProbeTransportMode.PASSIVE && !expectedResponse.isBlank()) {
            throw new IllegalArgumentException("Passive probe cannot declare an expected response: " + id);
        }
        if (transport == ProbeTransportMode.PASSIVE && identityResolution) {
            throw new IllegalArgumentException("Passive probe cannot declare identity resolution: " + id);
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

    public ProbeDefinition(String id, String displayName, String key, ProbeMode mode, String fallback,
                           boolean enabled, boolean manual, boolean automatic,
                           ProbeVerificationStatus verificationStatus, String category,
                           String source, String notes, String expectedResponse) {
        this(id, displayName, key, mode, fallback, enabled, manual, automatic, verificationStatus,
                category, source, notes, expectedResponse, ProbeTransportMode.INTERACTIVE, false, "");
    }

    public ProbeDefinition(String id, String displayName, String key, ProbeMode mode, String fallback,
                           boolean enabled, boolean manual, boolean automatic,
                           ProbeVerificationStatus verificationStatus, String category,
                           String source, String notes, String expectedResponse,
                           ProbeTransportMode transport, boolean identityResolution) {
        this(id, displayName, key, mode, fallback, enabled, manual, automatic, verificationStatus,
                category, source, notes, expectedResponse, transport, identityResolution, "");
    }

    /** Declares a passive probe fed by an inbound custom-payload channel. */
    public static ProbeDefinition passive(String id, String displayName, String key, String channel,
                                          boolean manual, boolean automatic,
                                          ProbeVerificationStatus verificationStatus, String category,
                                          String source, String notes) {
        return new ProbeDefinition(id, displayName, key, ProbeMode.TRANSLATE, "", true, manual, automatic,
                verificationStatus, category, source, notes, "", ProbeTransportMode.PASSIVE, false, channel);
    }

    /**
     * True when a response that is neither the configured fallback nor an unresolved
     * identifier proves the client resolved a translation key that only this target's
     * translation table defines. This is the locale-independent authoritative form.
     */
    public boolean canResolveIdentity() {
        return identityResolution && transport == ProbeTransportMode.INTERACTIVE
                && (mode == ProbeMode.TRANSLATE || mode == ProbeMode.METEOR);
    }

    /** True when this probe is fed by a declared, passively observable channel. */
    public boolean passiveChannelProbe() {
        return transport == ProbeTransportMode.PASSIVE && passiveChannel != null && !passiveChannel.isBlank();
    }

    /** True when this probe can ever produce an authoritative STRONG DETECTED result. */
    public boolean detectedCapable() {
        if (passiveChannelProbe()) return true;
        return canResolveIdentity() || (!expectedResponse.isBlank() && transport == ProbeTransportMode.INTERACTIVE);
    }

    public boolean automaticEligible(boolean interactiveAutomaticEnabled) {
        if (!enabled || !automatic) return false;
        return transport.safeForAutomatic() || interactiveAutomaticEnabled;
    }
}
