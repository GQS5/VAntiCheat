package site.vackstudio.vanticheat.config;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.detection.probe.ProbeDefinition;
import site.vackstudio.vanticheat.detection.probe.ProbeMode;
import site.vackstudio.vanticheat.detection.probe.ProbeVerificationStatus;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientDetectionConfigTest {
    private String bundledText() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/client-detection.yml")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void bundledDefinitionsParseWithVerificationMetadata() throws Exception {
        ClientDetectionConfig config = ClientDetectionConfig.parse(bundledText());
        assertTrue(config.enabled());
        assertEquals(40, config.timeoutTicks());
        assertEquals(0, config.betweenProbeTicks());
        assertEquals(38, config.probes().size());
        assertTrue(config.autoCheckOnJoin());
        assertEquals(20, config.autoCheckDelayTicks());
        assertEquals(32, config.maxConcurrentAutoChecks());
        assertEquals(27, config.automaticProbes().size());
        assertEquals(config.probes().stream().filter(probe -> probe.enabled() && probe.automatic())
                        .map(ProbeDefinition::id).toList(),
                config.automaticProbes().stream().map(ProbeDefinition::id).toList());
        assertTrue(config.probes().stream()
                .filter(probe -> probe.id().equals("xaeros-minimap"))
                .noneMatch(probe -> probe.enabled()));
            assertTrue(config.probes().stream()
                    .filter(probe -> probe.id().equals("xaeros-worldmap"))
                    .anyMatch(probe -> probe.enabled()));
        assertTrue(config.probes().stream()
                .filter(probe -> probe.id().equals("litematica"))
                .anyMatch(probe -> probe.enabled()));
        assertEquals(ProbeMode.METEOR, config.probes().get(0).mode());
        assertEquals(ProbeVerificationStatus.VERIFIED, config.probes().get(0).verificationStatus());
    }

    @Test
    void freshAndExistingFilesLoadWithoutOverwritingUserConfig() throws Exception {
        Path directory = Files.createTempDirectory("vanticheat-p12-6-");
        Path file = directory.resolve("client-detection.yml");
        assertTrue(Files.notExists(file));
        Files.writeString(file, bundledText());
        String before = Files.readString(file);

        ClientDetectionConfig config = ClientDetectionConfig.loadStrict(directory);

        assertEquals(38, config.probes().size());
        assertEquals(before, Files.readString(file));
    }

    @Test
    void invalidBooleanReportsFilePathExpectedTypeAndActualType() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                ClientDetectionConfig.parse("client-detection:\n  auto-check:\n    on-join: \"true\"\n"));

        assertEquals("file=client-detection.yml\n"
                + "path=client-detection.auto-check.on-join\n"
                + "expected=boolean\nactual=String", exception.getMessage());
    }

    @Test
    void numericAndQuotedBooleanValuesAreNotSilentlyAccepted() {
        IllegalArgumentException quoted = assertThrows(IllegalArgumentException.class, () ->
                ClientDetectionConfig.parse("client-detection:\n  enabled: \"false\"\n"));
        IllegalArgumentException numeric = assertThrows(IllegalArgumentException.class, () ->
                ClientDetectionConfig.parse("client-detection:\n  enabled: 0\n"));

        assertTrue(quoted.getMessage().contains("actual=String"));
        assertTrue(numeric.getMessage().contains("actual=Number"));
    }

    @Test
    void quotedProbeBooleanIsRejected() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                ClientDetectionConfig.parse("client-detection:\n"
                        + "  probes:\n"
                        + "    test:\n"
                        + "      display-name: Test\n"
                        + "      key: test.key\n"
                        + "      mode: KEYBIND\n"
                        + "      enabled: \"true\"\n"));

        assertTrue(exception.getMessage().contains("path=client-detection.probes.test.enabled"));
        assertTrue(exception.getMessage().contains("actual=String"));
    }

    @Test
    void inlineCommentsDoNotChangeUnquotedBooleanType() {
        ClientDetectionConfig config = ClientDetectionConfig.parse(
                "client-detection:\n  enabled: true # valid YAML comment\n");

        assertTrue(config.enabled());
    }

    @Test
    void explicitManualAndAutomaticPoliciesAreIndependent() {
        ClientDetectionConfig config = ClientDetectionConfig.parse("client-detection:\n"
                + "  enabled: true\n"
                + "  probes:\n"
                + "    experimental-mod:\n"
                + "      display-name: Experimental Mod\n"
                + "      key: example.mod.key\n"
                + "      mode: TRANSLATE\n"
                + "      enabled: true\n"
                + "      manual: true\n"
                + "      automatic: false\n"
                + "      verification: UNVERIFIED\n");

        assertEquals(1, config.manualProbes().size());
        assertTrue(config.automaticProbes().isEmpty());
    }

    @Test
    void invalidProbeMetadataHasPathSpecificErrors() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                ClientDetectionConfig.parse("client-detection:\n"
                        + "  probes:\n"
                        + "    invalid id:\n"
                        + "      display-name: Invalid\n"
                        + "      key: invalid.key\n"
                        + "      mode: TRANSLATE\n"));

        assertTrue(exception.getMessage().contains("path=client-detection.probes.invalid id"));
    }

    @Test
    void unknownModeAndMissingKeyAreRejected() {
        IllegalArgumentException unknownMode = assertThrows(IllegalArgumentException.class, () ->
                ClientDetectionConfig.parse("client-detection:\n"
                        + "  probes:\n"
                        + "    test:\n"
                        + "      display-name: Test\n"
                        + "      key: test.key\n"
                        + "      mode: UNKNOWN\n"));
        assertTrue(unknownMode.getMessage().contains("path=client-detection.probes.test.mode"));

        IllegalArgumentException missingKey = assertThrows(IllegalArgumentException.class, () ->
                ClientDetectionConfig.parse("client-detection:\n"
                        + "  probes:\n"
                        + "    test:\n"
                        + "      display-name: Test\n"
                        + "      mode: TRANSLATE\n"));
        assertTrue(missingKey.getMessage().contains("path=client-detection.probes.test.key"));
    }

    @Test
    void legacyAutomaticProbeListMigratesIntoDefinitionPolicy() {
        ClientDetectionConfig config = ClientDetectionConfig.parse("client-detection:\n"
                + "  auto-check:\n"
                + "    probes:\n"
                + "      - first\n"
                + "  probes:\n"
                + "    first:\n"
                + "      display-name: First\n"
                + "      key: first.key\n"
                + "      mode: TRANSLATE\n"
                + "    second:\n"
                + "      display-name: Second\n"
                + "      key: second.key\n"
                + "      mode: TRANSLATE\n");

        assertEquals(List.of("first"), config.autoProbeIds());
        assertEquals(List.of("first"), config.automaticProbes().stream().map(ProbeDefinition::id).toList());
    }

    @Test
    void catalogMetadataLoadsAndNewUnverifiedProbesAreManualOnly() throws Exception {
        ClientDetectionConfig config = ClientDetectionConfig.parse(bundledText());
        ProbeDefinition appleSkin = config.probeRegistry().require("apple-skin");
        ProbeDefinition ipn = config.probeRegistry().get("inventory-profiles-next");

        assertEquals("utility", appleSkin.category());
        assertEquals("text.autoconfig.appleskin.title", appleSkin.key());
        assertEquals(ProbeVerificationStatus.VERIFIED, appleSkin.verificationStatus());
        assertTrue(appleSkin.enabled());
        assertTrue(appleSkin.manual());
        assertTrue(!appleSkin.automatic());
        assertTrue(appleSkin.source().contains("AppleSkin"));
        assertTrue(appleSkin.notes().contains("Verified on Minecraft 1.21.11"));

        assertTrue(ipn != null);
        assertTrue(config.manualProbes().contains(ipn));
        assertTrue(!config.automaticProbes().contains(ipn));
        assertTrue(!config.probeRegistry().contains("unknown-mod"));
        assertTrue(config.probeRegistry().contains("emi-recipe-viewer"));
        assertTrue(config.probeRegistry().contains("jade-config"));
    }

    @Test
    void bundledDefaultsEnableConsecutiveSilenceBackoff() throws Exception {
        ClientDetectionConfig config = ClientDetectionConfig.parse(bundledText());

        assertEquals(10, config.shortTimeoutTicks());
        assertEquals(2, config.shortTimeoutAfterConsecutiveTimeouts());
        assertEquals(40, config.batchTimeoutTicks(0));
        assertEquals(40, config.batchTimeoutTicks(1));
        assertEquals(10, config.batchTimeoutTicks(2));
        assertEquals(10, config.batchTimeoutTicks(9));
    }

    @Test
    void shortTimeoutDefaultsToFullTimeoutWhenAbsent() {
        ClientDetectionConfig config = ClientDetectionConfig.parse(
                "client-detection:\n  enabled: true\n  timeout-ticks: 40\n");

        assertEquals(40, config.shortTimeoutTicks());
        assertEquals(2, config.shortTimeoutAfterConsecutiveTimeouts());
        assertEquals(40, config.batchTimeoutTicks(0));
        assertEquals(40, config.batchTimeoutTicks(100));
    }

    @Test
    void shortTimeoutAtOrAboveFullTimeoutDisablesAdaptation() {
        ClientDetectionConfig config = ClientDetectionConfig.parse(
                "client-detection:\n  enabled: true\n  timeout-ticks: 40\n"
                        + "  short-timeout-ticks: 40\n"
                        + "  short-timeout-after-consecutive-timeouts: 1\n");

        assertEquals(40, config.batchTimeoutTicks(0));
        assertEquals(40, config.batchTimeoutTicks(10));
    }

    @Test
    void invalidShortTimeoutValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ClientDetectionConfig.parse(
                "client-detection:\n  enabled: true\n  short-timeout-ticks: 0\n"));
        assertThrows(IllegalArgumentException.class, () -> ClientDetectionConfig.parse(
                "client-detection:\n  enabled: true\n"
                        + "  short-timeout-after-consecutive-timeouts: 0\n"));
    }

    @Test
    void catalogMetadataIsConsistentAcrossAllProbes() throws Exception {
        ClientDetectionConfig config = ClientDetectionConfig.parse(bundledText());

        for (ProbeDefinition probe : config.probes()) {
            assertTrue(!probe.category().isBlank(), "category missing: " + probe.id());
            if (probe.key().startsWith("key.") && probe.mode() != ProbeMode.METEOR) {
                assertEquals(ProbeMode.KEYBIND, probe.mode(), "key.* namespace requires KEYBIND: " + probe.id());
            }
        }
        assertEquals(ProbeMode.KEYBIND, config.probeRegistry().require("world-downloader").mode());
        assertEquals(ProbeMode.KEYBIND, config.probeRegistry().require("antiafk").mode());
        assertEquals(ProbeMode.KEYBIND, config.probeRegistry().require("ui-utils").mode());
        assertEquals("utility", config.probeRegistry().require("autoswitch").category());
        assertEquals("Wurst Client", config.probeRegistry().require("wurst").displayName());

        ProbeDefinition minimap = config.probeRegistry().require("xaeros-minimap");
        assertTrue(!minimap.enabled());
        assertTrue(!minimap.automatic());
    }

    @Test
    void verifiedWithoutEvidenceMetadataIsRejected() {
        String base = "client-detection:\n  probes:\n    test:\n"
                + "      display-name: Test\n      key: test.key\n      mode: TRANSLATE\n"
                + "      verification: VERIFIED\n";
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () ->
                ClientDetectionConfig.parse(base));
        assertTrue(missing.getMessage().contains("client-detection.probes.test.verification"));

        IllegalArgumentException notesOnly = assertThrows(IllegalArgumentException.class, () ->
                ClientDetectionConfig.parse(base + "      notes: \"some notes\"\n"));
        assertTrue(notesOnly.getMessage().contains("missing evidence metadata"));

        ClientDetectionConfig accepted = ClientDetectionConfig.parse(base
                + "      notes: \"some notes\"\n      source: \"some source\"\n");
        assertEquals(ProbeVerificationStatus.VERIFIED,
                accepted.probeRegistry().require("test").verificationStatus());
    }

    @Test
    void bundledVerifiedProbesCarryEvidenceMetadata() throws Exception {
        ClientDetectionConfig config = ClientDetectionConfig.parse(bundledText());

        assertTrue(!config.probeRegistry().verified().isEmpty());
        for (ProbeDefinition probe : config.probeRegistry().verified()) {
            assertTrue(!probe.notes().isBlank(), "notes missing: " + probe.id());
            assertTrue(!probe.source().isBlank(), "source missing: " + probe.id());
        }
    }

    @Test
    void disabledItemscrollerRemainsVisibleButExcludedFromSelection() throws Exception {
        ClientDetectionConfig config = ClientDetectionConfig.parse(bundledText());
        ProbeDefinition itemscroller = config.probeRegistry().require("itemscroller");

        assertTrue(!itemscroller.enabled());
        assertTrue(config.probeRegistry().contains("itemscroller"));
        assertTrue(config.automaticProbes().stream().noneMatch(probe -> probe.id().equals("itemscroller")));
        assertTrue(config.manualProbes().stream().noneMatch(probe -> probe.id().equals("itemscroller")));
    }

    @Test
    void enabledProbesAvoidKnownFabricatedIdentifierTemplate() throws Exception {
        ClientDetectionConfig config = ClientDetectionConfig.parse(bundledText());

        // "<mod>.hotkey.name.<action>" was disproved live in P28 for a real masa-family mod
        // (its real keys use "<mod>.config.hotkeys.name.<action>"). No enabled definition may
        // reintroduce the unprovable template without an in-hand artifact to back it.
        for (ProbeDefinition probe : config.probeRegistry().enabled()) {
            assertTrue(!probe.key().matches("^[a-z0-9_.-]+\\.hotkey\\.name\\..+$"),
                    "fabricated hotkey.name template in enabled probe: " + probe.id());
        }
        assertEquals(38, config.probeRegistry().size());
        assertEquals(config.probeRegistry().size(), config.probeRegistry().verifiedCount()
                + config.probeRegistry().unverifiedCount());
        assertEquals(config.probeRegistry().size() - config.probeRegistry().enabledCount(),
                config.probeRegistry().all().stream()
                        .filter(probe -> !probe.enabled()).count(),
                "every disabled definition is counted as disabled, not merely unverified");
    }

    @Test
    void expandedCatalogRegistryFiltersByEnabledManualAutomaticAndVerification() throws Exception {
        ClientDetectionConfig config = ClientDetectionConfig.parse(bundledText());

        assertEquals(38, config.probeRegistry().size());
        assertEquals(36, config.probeRegistry().enabledCount());
        assertEquals(36, config.probeRegistry().manualCount());
        assertEquals(27, config.probeRegistry().automaticCount());
        assertEquals(3, config.probeRegistry().verified().size());
        assertEquals(35, config.probeRegistry().unverified().size());
        assertTrue(config.probeRegistry().verified().stream()
                .map(ProbeDefinition::id).toList().containsAll(
                        List.of("meteor-client", "apple-skin", "jade-config-screen")));
        assertTrue(config.probeRegistry().get("apple-skin").manual());
        assertTrue(!config.probeRegistry().get("apple-skin").automatic());
        assertTrue(config.probeRegistry().automatic().stream()
                .noneMatch(probe -> probe.id().startsWith("inventory-profiles-next")));
    }
}
