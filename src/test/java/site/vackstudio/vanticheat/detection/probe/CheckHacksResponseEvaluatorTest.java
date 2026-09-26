package site.vackstudio.vanticheat.detection.probe;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.detection.DetectionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CheckHacksResponseEvaluatorTest {
    private static final ProbeDefinition PROBE = new ProbeDefinition(
            "meteor", "Meteor", "key.meteor.open", ProbeMode.METEOR, "", true,
            ProbeVerificationStatus.UNVERIFIED);

    @Test
    void preservesEmptyFallbackAndDetectionBranches() {
        assertEquals(DetectionStatus.CLEAN, CheckHacksResponseEvaluator.evaluate(
                PROBE, "", false, ProbeResponse.Outcome.RESPONSE));
        assertEquals(DetectionStatus.CLEAN, CheckHacksResponseEvaluator.evaluate(
                PROBE, "key.meteor.openx", false, ProbeResponse.Outcome.RESPONSE));
        assertEquals(DetectionStatus.DETECTED, CheckHacksResponseEvaluator.evaluate(
                PROBE, "key.meteor.open", false, ProbeResponse.Outcome.RESPONSE));
    }

    @Test
    void timeoutAndExploitProtectionAreNotClean() {
        assertEquals(DetectionStatus.PROTECTED, CheckHacksResponseEvaluator.evaluate(
                PROBE, "ignored", false, ProbeResponse.Outcome.TIMEOUT));
        ProbeDefinition keybind = new ProbeDefinition("keybind", "Keybind", "key.mod.toggle",
                ProbeMode.KEYBIND, "", true, ProbeVerificationStatus.UNVERIFIED);
        assertEquals(DetectionStatus.PROTECTED, CheckHacksResponseEvaluator.evaluate(
                keybind, "key.mod.toggle", true, ProbeResponse.Outcome.RESPONSE));
    }

    @Test
    void translateProbeWithNoFallbackDoesNotTreatEveryResponseAsClean() {
        ProbeDefinition xaero = new ProbeDefinition("xaeros-worldmap", "Xaero's World Map",
                "gui.xaero_worldmap", ProbeMode.TRANSLATE, "", true,
                ProbeVerificationStatus.UNVERIFIED);

        assertEquals(DetectionStatus.DETECTED, CheckHacksResponseEvaluator.evaluate(
                xaero, "Xaero's World Map", false, ProbeResponse.Outcome.RESPONSE));
    }
}
