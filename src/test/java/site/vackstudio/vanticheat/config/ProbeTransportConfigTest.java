package site.vackstudio.vanticheat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.vackstudio.vanticheat.detection.probe.ProbeDefinition;
import site.vackstudio.vanticheat.detection.probe.ProbeMode;
import site.vackstudio.vanticheat.detection.probe.ProbeTransportMode;
import site.vackstudio.vanticheat.detection.probe.ProbeVerificationStatus;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 2: transport and evidence declarations are validated configuration metadata. */
class ProbeTransportConfigTest {
    @TempDir
    private Path temporaryDirectory;

    private ClientDetectionConfig parse(String yaml) throws Exception {
        Path file = temporaryDirectory.resolve("client-detection.yml");
        Files.writeString(file, yaml);
        return ClientDetectionConfig.load(temporaryDirectory, Logger.getAnonymousLogger());
    }

    /** Rejection tests must see the real error, not the fail-closed disabled config. */
    private void parseStrict(String yaml) throws Exception {
        Path file = temporaryDirectory.resolve("client-detection.yml");
        Files.writeString(file, yaml);
        ClientDetectionConfig.loadStrict(temporaryDirectory);
    }

    private ClientDetectionConfig bundled() throws Exception {
        Path file = temporaryDirectory.resolve("client-detection.yml");
        try (InputStream stream = getClass().getResourceAsStream("/client-detection.yml")) {
            Files.write(file, stream.readAllBytes());
        }
        return ClientDetectionConfig.load(temporaryDirectory, Logger.getAnonymousLogger());
    }

    private static String probe(String id, String extra) {
        return probe(id, true, extra);
    }

    private static String probe(String id, boolean automatic, String extra) {
        return "    " + id + ":\n"
                + "      display-name: \"" + id + "\"\n"
                + "      key: \"mod." + id + ".title\"\n"
                + "      mode: TRANSLATE\n"
                + "      enabled: true\n"
                + "      manual: true\n"
                + (automatic ? "      automatic: true\n" : "")
                + "      verification: UNVERIFIED\n"
                + "      category: test\n"
                + extra;
    }

    @Test
    void everyBundledProbeDeclaresItsTransport() throws Exception {
        ClientDetectionConfig config = bundled();
        assertEquals(42, config.probes().size());
        for (ProbeDefinition probe : config.probes()) {
            if (probe.passiveChannelProbe()) {
                assertEquals(ProbeTransportMode.PASSIVE, probe.transport(), probe.id());
                assertFalse(probe.passiveChannel().isBlank(), probe.id() + " must declare its channel");
            } else {
                assertEquals(ProbeTransportMode.INTERACTIVE, probe.transport(),
                        probe.id() + " must declare the interactive sign transport explicitly");
            }
        }
        assertEquals(1, config.probeRegistry().passiveCount());
        assertEquals(41, config.probeRegistry().interactiveCount());
    }

    @Test
    void onlyTheThreeRecordedProbesDeclareIdentityResolution() throws Exception {
        ClientDetectionConfig config = bundled();
        assertEquals(List.of("apple-skin", "jade-config-screen", "meteor-client"),
                config.probes().stream().filter(ProbeDefinition::canResolveIdentity)
                        .map(ProbeDefinition::id).sorted().toList());
    }

    /**
     * P33: the three operator-requested clients are recorded but never enabled, because no
     * artifact was available to confirm a real identifier. Enabling a placeholder key would
     * silently report CLEAN forever and give false assurance.
     */
    @Test
    void requestedClientsWithoutArtifactsStayDisabledAndUnverified() throws Exception {
        ClientDetectionConfig config = bundled();
        for (String id : List.of("cezar-client", "nova-client", "forwarded")) {
            ProbeDefinition probe = config.probeRegistry().require(id);
            assertFalse(probe.enabled(), id + " must ship disabled");
            assertEquals(ProbeVerificationStatus.UNVERIFIED, probe.verificationStatus(), id);
            assertTrue(probe.manual(), id + " should be manual-only once its key is confirmed");
            assertFalse(probe.automatic(), id + " must not be automatic");
            assertTrue(probe.key().contains("unconfirmed-identifier"),
                    id + " must be visibly a placeholder, not a claimed key");
            assertFalse(probe.detectedCapable(), id + " cannot detect without a real identifier");
        }
    }

    /** P33: the passive channel map is explicit and is the only thing observed. */
    @Test
    void passiveChannelsAreDeclaredExplicitlyAndNotInferred() throws Exception {
        ClientDetectionConfig config = bundled();
        assertEquals(java.util.Map.of("jade:client_handshake", "jade-network-handshake"),
                config.passiveChannels());
        assertEquals(1, config.passiveProbeCount());
        assertFalse(config.passiveEnforce(),
                "passive evidence must not reach enforcement by default");
    }

    @Test
    void passiveEnforceIsParsedWhenExplicitlyEnabled() throws Exception {
        ClientDetectionConfig config = parse("client-detection:\n"
                + "  enabled: true\n"
                + "  timeout-ticks: 40\n"
                + "  between-probe-ticks: 0\n"
                + "  probes:\n"
                + probe("chan", "      transport: PASSIVE\n      passive-channel: \"mod:chan\"\n")
                + "  passive:\n"
                + "    enforce: true\n");
        assertTrue(config.passiveEnforce());
        assertEquals(java.util.Map.of("mod:chan", "chan"), config.passiveChannels());
    }

    @Test
    void absentTransportFailsClosedToInteractive() throws Exception {
        ClientDetectionConfig config = parse("client-detection:\n"
                + "  enabled: true\n"
                + "  timeout-ticks: 40\n"
                + "  between-probe-ticks: 0\n"
                + "  probes:\n"
                + probe("undeclared", ""));

        assertEquals(ProbeTransportMode.INTERACTIVE,
                config.probes().get(0).transport(),
                "an undeclared transport must never silently become automatic-safe");
        assertEquals(0, config.automaticEligibleProbes().size());
    }

    @Test
    void passiveProbeWithoutAChannelIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProbeDefinition("p", "P", "k",
                ProbeMode.TRANSLATE, "", true, true, true, ProbeVerificationStatus.UNVERIFIED,
                "test", "", "", "", ProbeTransportMode.PASSIVE, false, ""));
    }

    @Test
    void interactiveProbeWithAChannelIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProbeDefinition("i", "I", "k",
                ProbeMode.TRANSLATE, "", true, true, true, ProbeVerificationStatus.UNVERIFIED,
                "test", "", "", "", ProbeTransportMode.INTERACTIVE, false, "mod:chan"));
    }

    @Test
    void passiveTransportIsParsedAndBecomesAutomaticEligible() throws Exception {
        ClientDetectionConfig config = parse("client-detection:\n"
                + "  enabled: true\n"
                + "  timeout-ticks: 40\n"
                + "  between-probe-ticks: 0\n"
                + "  probes:\n"
                + probe("brand", "      transport: PASSIVE\n      passive-channel: \"test:brand\"\n"));

        assertEquals(ProbeTransportMode.PASSIVE, config.probes().get(0).transport());
        assertEquals(1, config.automaticEligibleProbes().size(),
                "a passive probe is safe for automatic use without any opt-in");
    }

    @Test
    void identityResolutionRequiresAnInteractiveTransport() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parseStrict("""
                client-detection:
                  enabled: true
                  timeout-ticks: 40
                  between-probe-ticks: 0
                  probes:
                    brand:
                      display-name: "Brand"
                      key: "mod.brand.title"
                      mode: TRANSLATE
                      enabled: true
                      manual: true
                      automatic: true
                      verification: UNVERIFIED
                      category: test
                      transport: PASSIVE
                      identity-resolution: true
                """));
        assertTrue(error.getMessage().contains("identity-resolution"),
                "the rejection must name the offending path");
    }

    @Test
    void invalidTransportValueIsRejectedWithTheFileScopedPath() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parseStrict("""
                client-detection:
                  enabled: true
                  timeout-ticks: 40
                  between-probe-ticks: 0
                  probes:
                    thing:
                      display-name: "Thing"
                      key: "mod.thing.title"
                      mode: TRANSLATE
                      enabled: true
                      manual: true
                      automatic: true
                      verification: UNVERIFIED
                      category: test
                      transport: TELEPATHIC
                """));
        assertTrue(error.getMessage().contains("client-detection.probes.thing.transport"));
        assertTrue(error.getMessage().contains("TELEPATHIC"));
    }

    @Test
    void interactiveAutomaticOptInIsParsedAndDefaultsToFalse() throws Exception {
        ClientDetectionConfig off = parse("client-detection:\n"
                + "  enabled: true\n"
                + "  timeout-ticks: 40\n"
                + "  between-probe-ticks: 0\n"
                + "  probes:\n"
                + probe("interactive-one", "")
                + "  auto-check:\n"
                + "    on-join: true\n");
        assertFalse(off.interactiveAutomatic());
        assertEquals(0, off.automaticEligibleProbes().size());
        assertFalse(off.warnings().isEmpty(), "reduced automatic coverage must be reported");

        ClientDetectionConfig on = parse("client-detection:\n"
                + "  enabled: true\n"
                + "  timeout-ticks: 40\n"
                + "  between-probe-ticks: 0\n"
                + "  probes:\n"
                + probe("interactive-one", "")
                + "  auto-check:\n"
                + "    on-join: true\n"
                + "    interactive: true\n");
        assertTrue(on.interactiveAutomatic());
        assertEquals(1, on.automaticEligibleProbes().size());
    }

    @Test
    void legacyAutoProbeListSyntaxIsStillMigrated() throws Exception {
        ClientDetectionConfig config = parse("client-detection:\n"
                + "  enabled: true\n"
                + "  timeout-ticks: 40\n"
                + "  between-probe-ticks: 0\n"
                + "  probes:\n"
                + probe("listed", false, "")
                + probe("unlisted", false, "")
                + "  auto-check:\n"
                + "    on-join: true\n"
                + "    probes:\n"
                + "      - listed\n");

        assertEquals(List.of("listed"), config.autoProbeIds());
        assertEquals(0, config.automaticEligibleProbes().size(),
                "the legacy list still cannot bypass the transport gate");
    }

    @Test
    void verifiedGuardrailStillRequiresEvidenceMetadata() {
        assertThrows(IllegalArgumentException.class, () -> parseStrict("""
                client-detection:
                  enabled: true
                  timeout-ticks: 40
                  between-probe-ticks: 0
                  probes:
                    claim:
                      display-name: "Claim"
                      key: "mod.claim.title"
                      mode: TRANSLATE
                      enabled: true
                      manual: true
                      automatic: true
                      verification: VERIFIED
                      category: test
                """));
    }

    @Test
    void detectedCapabilityIsDerivedNotConfigured() {
        ProbeDefinition noEvidence = new ProbeDefinition("a", "A", "mod.a.title", ProbeMode.TRANSLATE,
                "", true, true, true, ProbeVerificationStatus.VERIFIED, "test", "src", "notes", "");
        assertFalse(noEvidence.detectedCapable(),
                "a plausible identifier alone must never imply DETECTED capability");

        ProbeDefinition withIdentity = new ProbeDefinition("b", "B", "mod.b.title", ProbeMode.TRANSLATE,
                "", true, true, true, ProbeVerificationStatus.VERIFIED, "test", "src", "notes", "",
                ProbeTransportMode.INTERACTIVE, true);
        assertTrue(withIdentity.detectedCapable());
        assertTrue(withIdentity.canResolveIdentity());
    }
}
