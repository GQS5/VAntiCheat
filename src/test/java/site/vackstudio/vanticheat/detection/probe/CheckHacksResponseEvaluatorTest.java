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
        // The client resolved the submitted key to something that is not the recorded
        // default-locale string (for example a different locale). That is not proof the
        // target is absent, so it must stay ambiguous instead of being reported clean.
        ProbeEvaluation other = evaluate(appleSkin, "Different text", null);
        assertEquals(DetectionStatus.UNCERTAIN, other.status());
        assertEquals(ProbeEvidenceStrength.WEAK, other.evidenceStrength());
    }

    @Test
    void meteorUsesExactConfiguredEvidenceAndUnrelatedResponsesStayAmbiguous() {
        ProbeDefinition meteor = definition("meteor", "key.meteor.open", ProbeMode.METEOR,
                "", "Open GUI");

        assertEquals(DetectionStatus.DETECTED, evaluate(meteor, "Open GUI", null).status());
        assertEquals(DetectionStatus.DETECTED,
                evaluate(meteor, "different rendering", "translate:key.meteor.open").status());
        assertEquals(DetectionStatus.CLEAN,
                evaluate(meteor, "different rendering", "translate:other.client.open").status());
        // A resolved-but-unmatched rendering is ambiguous, never a clean bill of health.
        assertEquals(DetectionStatus.UNCERTAIN, evaluate(meteor, "unrelated response", null).status());
        assertEquals(DetectionStatus.CLEAN, evaluate(meteor, meteor.fallback(), null).status());
    }

    /**
     * A probe that declares identity-resolution accepts any non-fallback resolution of its
     * own target-specific key. This is what makes detection locale-independent: the string
     * the client renders is irrelevant, only the fact that it could resolve the key is not.
     */
    @Test
    void declaredIdentityResolutionIsLocaleIndependentAndStillExact() {
        ProbeDefinition meteor = identityProbe("meteor-client", "key.meteor-client.open-gui");

        assertEquals(DetectionStatus.DETECTED, evaluate(meteor, "Open GUI", null).status());
        // Same key, different display locale.
        assertEquals(DetectionStatus.DETECTED, evaluate(meteor, "Ouvrir l'interface", null).status());
        assertEquals(DetectionStatus.DETECTED, evaluate(meteor, "\u30A4\u30F3\u30D5\u30E9\u30A4\u958B\u304D", null).status());
        // A client that cannot resolve the key still returns the sentinel, or nothing.
        assertEquals(DetectionStatus.CLEAN, evaluate(meteor, meteor.fallback(), null).status());
        assertEquals(DetectionStatus.CLEAN, evaluate(meteor, "", null).status());
        // A different identifier is a different key entirely, never this target.
        assertEquals(DetectionStatus.CLEAN, evaluate(meteor, "other.client.open", null).status());
        // Returning the key literally is the exploit-preventer path, not evidence.
        assertEquals(DetectionStatus.PROTECTED, evaluate(meteor, meteor.key(), null).status());
    }

    @Test
    void identityResolutionIsNotInferredAndNotGrantedByAPlausibleIdentifier() {
        // Same shape, but the operator never declared the key target-specific.
        ProbeDefinition undeclared = definition("undeclared", "mod.some.title", ProbeMode.TRANSLATE, "", "");
        assertEquals(DetectionStatus.UNCERTAIN, evaluate(undeclared, "Any localized text", null).status());

        // A different translation key is not this target's key.
        ProbeDefinition meteor = identityProbe("meteor-client", "key.meteor-client.open-gui");
        assertEquals(DetectionStatus.CLEAN,
                evaluate(meteor, "Localized text", "translate:some.other.mod.title").status());
    }

    private static ProbeDefinition identityProbe(String id, String key) {
        return new ProbeDefinition(id, id, key, ProbeMode.TRANSLATE, "", true, true, false,
                ProbeVerificationStatus.UNVERIFIED, "test", "", "", "",
                ProbeTransportMode.INTERACTIVE, true);
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
