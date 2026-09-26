package site.vackstudio.vanticheat.detection.probe;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P12.4/P12.5 probe-integrity guards for the Player C (Meteor) trace.
 * These tests lock configuration selection and evaluator semantics only;
 * they do not prove real-client Meteor detection.
 * P12.5 note: "Open GUI" below is the exact resolved value of
 * key.meteor-client.open-gui from assets/meteor-client/lang/en_us.json
 * in the pinned real build meteor-client-1.21.11-86
 * (SHA-256 93390cfdb1030e47f116357ba421c90a9b1ca4832b6551c2dec1ade5eeb42e71).
 */
class MeteorProbeIntegrityTest {
    @TempDir
    private Path temporaryDirectory;

    private ClientDetectionConfig bundled() throws Exception {
        Path file = temporaryDirectory.resolve("client-detection.yml");
        try (InputStream stream = getClass().getResourceAsStream("/client-detection.yml")) {
            Files.write(file, stream.readAllBytes());
        }
        return ClientDetectionConfig.load(temporaryDirectory, Logger.getAnonymousLogger());
    }

    @Test
    void exactlyOneMeteorProbeExistsAndIsEnabled() throws Exception {
        ClientDetectionConfig config = bundled();
        List<ProbeDefinition> meteor = config.probes().stream()
                .filter(probe -> probe.id().toLowerCase(java.util.Locale.ROOT).contains("meteor")
                        || probe.displayName().toLowerCase(java.util.Locale.ROOT).contains("meteor")
                        || probe.key().toLowerCase(java.util.Locale.ROOT).contains("meteor"))
                .toList();

        assertEquals(List.of("meteor-client"), meteor.stream().map(ProbeDefinition::id).toList());
        ProbeDefinition probe = meteor.get(0);
        assertTrue(probe.enabled());
        assertEquals(ProbeMode.METEOR, probe.mode());
        assertEquals("key.meteor-client.open-gui", probe.key());
        assertEquals(ProbeVerificationStatus.UNVERIFIED, probe.verificationStatus());
    }

    @Test
    void meteorProbeIsSelectedManuallyAndAutomatically() throws Exception {
        ClientDetectionConfig config = bundled();

        assertTrue(config.probes().stream()
                .filter(ProbeDefinition::enabled)
                .anyMatch(probe -> probe.id().equals("meteor-client")));
        assertTrue(config.automaticProbes().stream()
                .anyMatch(probe -> probe.id().equals("meteor-client")));
    }

    @Test
    void meteorEvaluatorBranchesMatchCheckHacksSemantics() throws Exception {
        ProbeDefinition meteor = bundled().probes().stream()
                .filter(probe -> probe.id().equals("meteor-client"))
                .findFirst()
                .orElseThrow();

        assertEquals(DetectionStatus.CLEAN, CheckHacksResponseEvaluator.evaluate(
                meteor, meteor.fallback(), false, ProbeResponse.Outcome.RESPONSE));
        assertEquals(DetectionStatus.DETECTED, CheckHacksResponseEvaluator.evaluate(
                meteor, meteor.key(), false, ProbeResponse.Outcome.RESPONSE));
        assertEquals(DetectionStatus.DETECTED, CheckHacksResponseEvaluator.evaluate(
                meteor, "Open GUI", false, ProbeResponse.Outcome.RESPONSE));
        assertEquals(DetectionStatus.CLEAN, CheckHacksResponseEvaluator.evaluate(
                meteor, "", false, ProbeResponse.Outcome.RESPONSE));
    }

    @Test
    void transportFailuresNeverMasqueradeAsClean() throws Exception {
        ProbeDefinition meteor = bundled().probes().stream()
                .filter(probe -> probe.id().equals("meteor-client"))
                .findFirst()
                .orElseThrow();

        assertEquals(DetectionStatus.PROTECTED, CheckHacksResponseEvaluator.evaluate(
                meteor, "anything", false, ProbeResponse.Outcome.TIMEOUT));
        assertEquals(DetectionStatus.ERROR, CheckHacksResponseEvaluator.evaluate(
                meteor, "anything", false, ProbeResponse.Outcome.DISCONNECTED));
        assertEquals(DetectionStatus.ERROR, CheckHacksResponseEvaluator.evaluate(
                meteor, "anything", false, ProbeResponse.Outcome.ERROR));
    }

    @Test
    void xaeroProbesRemainDisabledAndUnselected() throws Exception {
        ClientDetectionConfig config = bundled();

        for (String id : List.of("xaeros-minimap", "xaeros-worldmap")) {
            assertTrue(config.probes().stream()
                    .filter(probe -> probe.id().equals(id))
                    .noneMatch(ProbeDefinition::enabled));
            assertTrue(config.automaticProbes().stream()
                    .noneMatch(probe -> probe.id().equals(id)));
        }
    }
}
