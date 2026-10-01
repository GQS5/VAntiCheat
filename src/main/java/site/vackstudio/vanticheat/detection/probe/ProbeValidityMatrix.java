package site.vackstudio.vanticheat.detection.probe;

import site.vackstudio.vanticheat.detection.DetectionStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Derived validity matrix for the whole configured catalog.
 *
 * <p>Every field is computed from the probe's own declared metadata. Nothing here is a
 * hard-coded per-id table, so the matrix can never claim a capability the configuration
 * does not declare, and it can never promote a probe's verification state. A row reports
 * what the protocol can actually carry for that probe, and where that is unknown it says
 * so instead of guessing.
 */
public final class ProbeValidityMatrix {

    /** How well a probe's declared evidence has been established. */
    public enum EvidenceQuality {
        /** Real-client evidence is recorded in the probe's source and notes. */
        RECORDED,
        /** An operator declared the key target-specific; no live evidence is recorded. */
        DECLARED,
        /** No evidence of any kind; the probe can only report ambiguity. */
        UNPROVEN
    }

    /** What the server can observe for this probe over the current transport. */
    public enum IdentityTransport {
        /** The sign round-trip can return the raw component identity string. */
        EXACT_COMPONENT_IDENTITY,
        /** Only the client's rendering of the submitted key is observable. */
        RENDERED_TEXT_ONLY,
        /** Nothing about this target is observable without a response. */
        NOT_OBSERVABLE
    }

    public record Row(String id, String displayName, String category, ProbeMode mode,
                      ProbeTransportMode transport, boolean enabled, boolean manual,
                      boolean automatic, boolean automaticEligible,
                      String expectedArtifact, IdentityTransport identityTransport,
                      ProbeEvidenceStrength strongestEvidence, EvidenceQuality evidenceQuality,
                      String cleanClientBehavior, String targetClientBehavior,
                      boolean exactIdentityTransportable, boolean detectedCapable,
                      ProbeVerificationStatus verificationStatus, boolean interactiveUiRisk,
                      String passiveChannel, String source, String notes) {

        /** True when this probe is answered by a declared, passively observed channel. */
        public boolean passiveChannelProbe() {
            return !passiveChannel.isBlank();
        }

        /** The strongest terminal status this probe can legitimately produce. */
        public String reachableConclusion() {
            if (!detectedCapable) return "UNCERTAIN (cannot reach DETECTED)";
            return "DETECTED (authoritative) / CLEAN / TIMEOUT / UNSUPPORTED";
        }

        /** A VERIFIED row must carry real recorded evidence; others must not claim it. */
        public boolean honest() {
            if (verificationStatus == ProbeVerificationStatus.VERIFIED) {
                return evidenceQuality == EvidenceQuality.RECORDED
                        && !source.isBlank() && !notes.isBlank();
            }
            return evidenceQuality != EvidenceQuality.RECORDED;
        }
    }

    private final ProbeRegistry registry;
    private final boolean interactiveAutomatic;
    private final Map<String, Row> rows;

    public ProbeValidityMatrix(ProbeRegistry registry, boolean interactiveAutomatic) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.interactiveAutomatic = interactiveAutomatic;
        LinkedHashMap<String, Row> indexed = new LinkedHashMap<>();
        for (ProbeDefinition probe : registry.all()) {
            indexed.put(probe.id(), row(probe));
        }
        rows = Map.copyOf(indexed);
    }

    private Row row(ProbeDefinition probe) {
        EvidenceQuality quality = evidenceQuality(probe);
        IdentityTransport identity = identityTransport(probe);
        boolean detectedCapable = probe.detectedCapable();
        return new Row(probe.id(), probe.displayName(), probe.category(), probe.mode(),
                probe.transport(), probe.enabled(), probe.manual(), probe.automatic(),
                probe.automaticEligible(interactiveAutomatic),
                expectedArtifact(probe), identity,
                strongestEvidence(probe, detectedCapable), quality,
                cleanClientBehavior(quality), targetClientBehavior(quality),
                identity != IdentityTransport.NOT_OBSERVABLE, detectedCapable,
                probe.verificationStatus(), probe.transport().opensClientUi(),
                probe.passiveChannel() == null ? "" : probe.passiveChannel(),
                probe.source(), probe.notes());
    }

    private static EvidenceQuality evidenceQuality(ProbeDefinition probe) {
        if (probe.verificationStatus() == ProbeVerificationStatus.VERIFIED) return EvidenceQuality.RECORDED;
        if (probe.passiveChannelProbe()) return EvidenceQuality.DECLARED;
        if (probe.canResolveIdentity()) return EvidenceQuality.DECLARED;
        return EvidenceQuality.UNPROVEN;
    }

    private static IdentityTransport identityTransport(ProbeDefinition probe) {
        if (probe.passiveChannelProbe()) return IdentityTransport.EXACT_COMPONENT_IDENTITY;
        return probe.mode() == ProbeMode.KEYBIND ? IdentityTransport.EXACT_COMPONENT_IDENTITY
                : IdentityTransport.RENDERED_TEXT_ONLY;
    }

    private static ProbeEvidenceStrength strongestEvidence(ProbeDefinition probe, boolean detectedCapable) {
        if (!detectedCapable) return ProbeEvidenceStrength.WEAK;
        if (probe.canResolveIdentity()) return ProbeEvidenceStrength.STRONG;
        return probe.expectedResponse().isBlank() ? ProbeEvidenceStrength.WEAK : ProbeEvidenceStrength.STRONG;
    }

    private static String expectedArtifact(ProbeDefinition probe) {
        if (probe.passiveChannelProbe()) {
            return "inbound custom payload on channel '" + probe.passiveChannel() + "'";
        }
        return switch (probe.mode()) {
            case TRANSLATE -> "client resolution of translate key '" + probe.key() + "'";
            case METEOR -> "client resolution of Meteor key '" + probe.key() + "'";
            case KEYBIND -> "client keybind identity for '" + probe.key() + "'";
        };
    }

    private static String cleanClientBehavior(EvidenceQuality quality) {
        String sentinelled = "echoes the configured fallback sentinel";
        return switch (quality) {
            case RECORDED -> "recorded: " + sentinelled;
            case DECLARED -> "expected: " + sentinelled + " (not live-validated)";
            case UNPROVEN -> "assumed: " + sentinelled + " (unverified)";
        };
    }

    private static String targetClientBehavior(EvidenceQuality quality) {
        String resolved = "resolves the key and returns its own rendering";
        return switch (quality) {
            case RECORDED -> "recorded: " + resolved;
            case DECLARED -> "expected: " + resolved + " (not live-validated)";
            case UNPROVEN -> "assumed: " + resolved + "; ambiguous text is capped at UNCERTAIN";
        };
    }

    public Row row(String probeId) { return rows.get(probeId); }

    public List<Row> rows() { return List.copyOf(rows.values()); }

    public ProbeRegistry registry() { return registry; }

    public int size() { return rows.size(); }

    public long passiveCount() { return rows.values().stream().filter(row -> row.transport() == ProbeTransportMode.PASSIVE).count(); }

    public long interactiveCount() { return rows.values().stream().filter(row -> row.transport() == ProbeTransportMode.INTERACTIVE).count(); }

    public long manualCount() { return registry.manualCount(); }

    public long automaticConfiguredCount() { return registry.automaticCount(); }

    public long automaticEligibleCount() {
        return rows.values().stream().filter(Row::automaticEligible).count();
    }

    public long detectedCapableCount() { return rows.values().stream().filter(Row::detectedCapable).count(); }

    public long verifiedCount() { return registry.verifiedCount(); }

    public long unverifiedCount() { return registry.unverifiedCount(); }

    public long disabledCount() { return rows.values().stream().filter(row -> !row.enabled()).count(); }

    public long byEvidenceQuality(EvidenceQuality quality) {
        return rows.values().stream().filter(row -> row.evidenceQuality() == quality).count();
    }

    /** Rows that would open client UI if they ever ran unattended. */
    public List<Row> interactiveAutomaticRisks() {
        return rows.values().stream()
                .filter(Row::automatic)
                .filter(Row::interactiveUiRisk)
                .filter(row -> !row.automaticEligible())
                .toList();
    }

    /** Every probe whose only possible outcome is ambiguity. */
    public List<Row> ambiguityCapped() {
        return rows.values().stream().filter(row -> !row.detectedCapable()).toList();
    }

    public DetectionStatus worstCase(Row row) {
        return row.detectedCapable() ? DetectionStatus.DETECTED : DetectionStatus.UNCERTAIN;
    }
}
