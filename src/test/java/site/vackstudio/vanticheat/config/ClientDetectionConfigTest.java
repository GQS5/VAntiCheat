package site.vackstudio.vanticheat.config;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.detection.probe.ProbeMode;
import site.vackstudio.vanticheat.detection.probe.ProbeVerificationStatus;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
        assertEquals(1, config.betweenProbeTicks());
        assertEquals(29, config.probes().size());
        assertTrue(config.autoCheckOnJoin());
        assertEquals(20, config.autoCheckDelayTicks());
        assertEquals(32, config.maxConcurrentAutoChecks());
        assertEquals(28, config.automaticProbes().size());
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
        assertEquals(ProbeVerificationStatus.UNVERIFIED, config.probes().get(0).verificationStatus());
    }

    @Test
    void freshAndExistingFilesLoadWithoutOverwritingUserConfig() throws Exception {
        Path directory = Files.createTempDirectory("vanticheat-p12-6-");
        Path file = directory.resolve("client-detection.yml");
        assertTrue(Files.notExists(file));
        Files.writeString(file, bundledText());
        String before = Files.readString(file);

        ClientDetectionConfig config = ClientDetectionConfig.loadStrict(directory);

        assertEquals(29, config.probes().size());
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
}
