package site.vackstudio.vanticheat.detection.probe;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.detection.DetectionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CheckHacksResponseEvaluatorTest {
    @Test
    void translateRequiresConfiguredExactTextOrComponentIdentity() {
        ProbeDefinition appleSkin = definition("apple-skin", "text.autoconfig.appleskin.title",
                ProbeMode.TRANSLATE, "", "AppleSkin");

        assertEquals(DetectionStatus.DETECTED, evaluate(appleSkin, " AppleSkin ", null).status());
        assertEquals(DetectionStatus.DETECTED,
                evaluate(appleSkin, "AppleSkin", "translate:text.autoconfig.appleskin.title").status());
        assertEquals(DetectionStatus.CLEAN,
                evaluate(appleSkin, "AppleSkin", "translate:other.mod.title").status());
        assertEquals(DetectionStatus.CLEAN, evaluate(appleSkin, appleSkin.fallback(), null).status());
        assertEquals(DetectionStatus.PROTECTED, evaluate(appleSkin, appleSkin.key(), null).status());
        assertEquals(DetectionStatus.CLEAN, evaluate(appleSkin, "Different text", null).status());
    }

    @Test
    void unconfiguredTranslationTextIsUncertainRatherThanDetection() {
        ProbeDefinition translation = definition("translated", "mod.translation.title",
                ProbeMode.TRANSLATE, "", "");
        ProbeEvaluation evaluation = evaluate(translation, "Localized title", null);

        assertEquals(DetectionStatus.UNCERTAIN, evaluation.status());
        assertEquals(ProbeEvidenceStrength.WEAK, evaluation.evidenceStrength());
        assertEquals(DetectionStatus.CLEAN, evaluate(translation, "mod.other.title", null).status());
        assertEquals(DetectionStatus.CLEAN, evaluate(translation, translation.fallback(), null).status());
    }

    @Test
    void keybindRequiresExactIdentityOrConfiguredResponseAndRejectsIdentifierCollisions() {
        ProbeDefinition keybind = definition("keybind", "key.client.open", ProbeMode.KEYBIND,
                "", "");
        ProbeDefinition knownOutput = definition("keybind-known", "key.client.open", ProbeMode.KEYBIND,
                "", "Right Shift");

        assertEquals(DetectionStatus.DETECTED,
                evaluate(keybind, "Right Shift", "keybind:key.client.open").status());
        assertEquals(DetectionStatus.CLEAN,
                evaluate(keybind, "Right Shift", "keybind:key.other.open").status());
        assertEquals(DetectionStatus.CLEAN, evaluate(keybind, keybind.key(), null).status());
        assertEquals(DetectionStatus.CLEAN, evaluate(keybind, "key.client.open_extra", null).status());
        assertEquals(DetectionStatus.DETECTED, evaluate(knownOutput, "Right Shift", null).status());
        assertEquals(DetectionStatus.UNCERTAIN, evaluate(keybind, "Right Shift", null).status());
    }

    @Test
    void meteorUsesExactConfiguredEvidenceAndUnrelatedResponsesAreClean() {
        ProbeDefinition meteor = definition("meteor", "key.meteor.open", ProbeMode.METEOR,
                "", "Open GUI");

        assertEquals(DetectionStatus.DETECTED, evaluate(meteor, "Open GUI", null).status());
        assertEquals(DetectionStatus.DETECTED,
                evaluate(meteor, "different rendering", "translate:key.meteor.open").status());
        assertEquals(DetectionStatus.CLEAN,
                evaluate(meteor, "different rendering", "translate:other.client.open").status());
        assertEquals(DetectionStatus.CLEAN, evaluate(meteor, "unrelated response", null).status());
        assertEquals(DetectionStatus.CLEAN, evaluate(meteor, meteor.fallback(), null).status());
    }

    @Test
    void transportOutcomesRemainSeparateFromEvaluatorClassification() {
        ProbeDefinition probe = definition("translate", "mod.title", ProbeMode.TRANSLATE, "", "Text");

        assertEquals(DetectionStatus.TIMEOUT, CheckHacksResponseEvaluator.evaluateTransport(
                probe, "", null, false, ProbeResponse.Outcome.TIMEOUT).status());
        assertEquals(DetectionStatus.ERROR, CheckHacksResponseEvaluator.evaluateTransport(
                probe, "", null, false, ProbeResponse.Outcome.DISCONNECTED).status());
        assertEquals(DetectionStatus.ERROR, CheckHacksResponseEvaluator.evaluateTransport(
                probe, "", null, false, ProbeResponse.Outcome.ERROR).status());
        assertEquals(DetectionStatus.UNSUPPORTED, CheckHacksResponseEvaluator.evaluateTransport(
                probe, "", null, false, ProbeResponse.Outcome.UNSUPPORTED).status());
        assertEquals(DetectionStatus.SKIPPED, CheckHacksResponseEvaluator.evaluateTransport(
                probe, "", null, false, ProbeResponse.Outcome.SKIPPED).status());
        assertEquals(DetectionStatus.SKIPPED, CheckHacksResponseEvaluator.evaluateTransport(
                probe, "", null, false, ProbeResponse.Outcome.CANCELLED).status());
    }

    @Test
    void exploitProtectionRemainsProtectedNotDetected() {
        ProbeDefinition keybind = definition("keybind", "key.mod.toggle", ProbeMode.KEYBIND, "", "");
        assertEquals(DetectionStatus.PROTECTED, CheckHacksResponseEvaluator.evaluateTransport(
                keybind, keybind.key(), null, true, ProbeResponse.Outcome.RESPONSE).status());
    }

    private static ProbeEvaluation evaluate(ProbeDefinition probe, String value, String identity) {
        return CheckHacksResponseEvaluator.evaluateDetailed(probe, value, identity, false);
    }

    private static ProbeDefinition definition(String id, String key, ProbeMode mode,
                                             String fallback, String expectedResponse) {
        return new ProbeDefinition(id, id, key, mode, fallback, true, true, false,
                ProbeVerificationStatus.UNVERIFIED, "test", "", "", expectedResponse);
    }
}
