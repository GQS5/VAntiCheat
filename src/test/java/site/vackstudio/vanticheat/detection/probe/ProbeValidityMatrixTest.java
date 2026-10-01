package site.vackstudio.vanticheat.detection.probe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.vackstudio.vanticheat.config.ClientDetectionConfig;
import site.vackstudio.vanticheat.detection.DetectionStatus;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6: the validity matrix is derived from configuration, so it can only report
 * capabilities the catalog actually declares. It must never inflate verification and must
 * never let a probe claim DETECTED just because its configuration looks plausible.
 */
class ProbeValidityMatrixTest {
    @TempDir
    private Path temporaryDirectory;

    private ClientDetectionConfig config;
    private ProbeValidityMatrix matrix;

    @BeforeEach
    void load() throws Exception {
        Path file = temporaryDirectory.resolve("client-detection.yml");
        try (InputStream stream = getClass().getResourceAsStream("/client-detection.yml")) {
            Files.write(file, stream.readAllBytes());
        }
        config = ClientDetectionConfig.load(temporaryDirectory, Logger.getAnonymousLogger());
        matrix = new ProbeValidityMatrix(config.probeRegistry(), false);
    }

    @Test
    void matrixCoversEveryConfiguredProbe() {
        assertEquals(config.probeRegistry().size(), matrix.size());
        assertEquals(42, matrix.size());
        for (ProbeDefinition probe : config.probes()) {
            assertNotNull(matrix.row(probe.id()), "every probe needs a matrix row: " + probe.id());
        }
    }

    @Test
    void countsMatchTheCatalogAndAreNotInflated() {
        assertEquals(3, matrix.verifiedCount(), "P33 must not inflate the verified count");
        assertEquals(39, matrix.unverifiedCount());
        assertEquals(5, matrix.disabledCount());
        assertEquals(42, matrix.passiveCount() + matrix.interactiveCount());
        assertEquals(1, matrix.passiveCount(), "exactly one probe is served by a passive channel");
        assertEquals(41, matrix.interactiveCount(),
                "the sign round-trip is interactive for every other probe");
    }

    @Test
    void everyInteractiveAutomaticProbeIsReportedAsAGameplayRisk() {
        List<ProbeValidityMatrix.Row> risks = matrix.interactiveAutomaticRisks();
        assertFalse(risks.isEmpty(), "the default must block the interactive automatic set");
        for (ProbeValidityMatrix.Row row : risks) {
            assertTrue(row.automatic(), row.id() + " is configured automatic");
            assertTrue(row.interactiveUiRisk(), row.id() + " opens client UI");
            assertFalse(row.automaticEligible(), row.id() + " must not be automatic-eligible by default");
        }
    }

    @Test
    void automaticEligibleIsExactlyThePassiveProbeByDefault() {
        assertEquals(1, matrix.automaticEligibleCount(),
                "the passive channel probe is the only automatic-eligible probe by default");
        assertEquals(List.of("jade-network-handshake"),
                matrix.rows().stream().filter(ProbeValidityMatrix.Row::automaticEligible)
                        .map(ProbeValidityMatrix.Row::id).toList());
        ProbeValidityMatrix opted = new ProbeValidityMatrix(config.probeRegistry(), true);
        assertEquals(config.probeRegistry().automaticCount(), opted.automaticEligibleCount(),
                "the interactive opt-in still admits every configured automatic probe");
    }

    @Test
    void onlyRecordedEvidenceProbesCanClaimDetectedCapability() {
        List<ProbeValidityMatrix.Row> capable = matrix.rows().stream()
                .filter(ProbeValidityMatrix.Row::detectedCapable).toList();

        assertEquals(4, capable.size(),
                "three recorded interactive probes plus the proven passive channel probe");
        for (ProbeValidityMatrix.Row row : capable) {
            assertTrue(row.evidenceQuality() == ProbeValidityMatrix.EvidenceQuality.RECORDED
                            || row.evidenceQuality() == ProbeValidityMatrix.EvidenceQuality.DECLARED,
                    row.id() + " must have a real evidence basis to detect");
        }
    }

    @Test
    void ambiguityCappedProbesCannotClaimDetection() {
        List<ProbeValidityMatrix.Row> capped = matrix.ambiguityCapped();
        assertEquals(38, capped.size(), "38 probes are capped at UNCERTAIN by protocol limits");
        assertFalse(capped.stream().anyMatch(row -> row.passiveChannelProbe()),
                "a passive channel probe is never ambiguity capped");
        for (ProbeValidityMatrix.Row row : capped) {
            assertFalse(row.detectedCapable());
            assertEquals(ProbeEvidenceStrength.WEAK, row.strongestEvidence());
            assertTrue(row.reachableConclusion().contains("cannot reach DETECTED"));
            assertEquals(DetectionStatus.UNCERTAIN, matrix.worstCase(row));
        }
    }

    @Test
    void verifiedRowsCarryRecordedEvidenceAndOthersDoNotClaimIt() {
        for (ProbeValidityMatrix.Row row : matrix.rows()) {
            assertTrue(row.honest(), row.id() + " must report its evidence honestly");
            if (row.verificationStatus() == ProbeVerificationStatus.VERIFIED) {
                assertEquals(ProbeValidityMatrix.EvidenceQuality.RECORDED, row.evidenceQuality());
                assertFalse(row.source().isBlank(), row.id() + " needs a source");
                assertFalse(row.notes().isBlank(), row.id() + " needs recorded notes");
            } else {
                assertTrue(row.evidenceQuality() != ProbeValidityMatrix.EvidenceQuality.RECORDED,
                        row.id() + " must not claim recorded evidence");
            }
        }
    }

    @Test
    void thePassiveProbeIsUnverifiedButDetectedCapable() {
        ProbeValidityMatrix.Row row = matrix.row("jade-network-handshake");
        assertNotNull(row);
        assertTrue(row.detectedCapable());
        assertTrue(row.passiveChannelProbe());
        assertEquals(ProbeTransportMode.PASSIVE, row.transport());
        assertEquals(ProbeVerificationStatus.UNVERIFIED, row.verificationStatus(),
                "proven from bytecode, never promoted to VERIFIED without a live run");
        assertEquals(ProbeValidityMatrix.EvidenceQuality.DECLARED, row.evidenceQuality(),
                "the channel is declared and artifact-proven, but never live-verified");
    }

    @Test
    void theThreeVerifiedProbesRemainTheOnlyRecordedOnes() {
        assertEquals(List.of("apple-skin", "jade-config-screen", "meteor-client"),
                matrix.rows().stream()
                        .filter(row -> row.verificationStatus() == ProbeVerificationStatus.VERIFIED)
                        .map(ProbeValidityMatrix.Row::id)
                        .sorted()
                        .toList());
    }

    @Test
    void disabledProbesAreReportedAsDisabledAndNotDetectedCapable() {
        for (String id : List.of("xaeros-minimap", "itemscroller")) {
            ProbeValidityMatrix.Row row = matrix.row(id);
            assertNotNull(row, id + " must remain visible in the matrix");
            assertFalse(row.enabled());
            assertFalse(row.detectedCapable());
        }
    }

    @Test
    void everyRowDescribesItsOwnObservableArtifact() {
        for (ProbeValidityMatrix.Row row : matrix.rows()) {
            assertTrue(row.expectedArtifact().contains(row.id().isEmpty() ? "" : "key")
                            || row.expectedArtifact().contains("'"),
                    row.id() + " must describe a concrete observable artifact");
            assertTrue(row.cleanClientBehavior().contains("sentinel"));
            assertTrue(row.targetClientBehavior().contains("resolves"));
        }
    }

    @Test
    void translationProbesCannotCarryExactComponentIdentity() {
        // The sign protocol returns the client's rendering, so TRANSLATE probes reached
        // through the sign editor are RENDERED_TEXT_ONLY. A passive channel probe is the one
        // exception: its identity is the exact channel the client itself emitted.
        for (ProbeValidityMatrix.Row row : matrix.rows()) {
            if (row.passiveChannelProbe()) {
                assertEquals(ProbeValidityMatrix.IdentityTransport.EXACT_COMPONENT_IDENTITY,
                        row.identityTransport());
            } else if (row.mode() == ProbeMode.KEYBIND) {
                assertEquals(ProbeValidityMatrix.IdentityTransport.EXACT_COMPONENT_IDENTITY,
                        row.identityTransport());
            } else {
                assertEquals(ProbeValidityMatrix.IdentityTransport.RENDERED_TEXT_ONLY,
                        row.identityTransport());
            }
        }
    }
}
