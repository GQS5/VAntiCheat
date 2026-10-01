package site.vackstudio.vanticheat.detection.probe;

import site.vackstudio.vanticheat.detection.DetectionStatus;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Compatibility-named evaluator; all behavior is driven by generic probe metadata. */
public final class CheckHacksResponseEvaluator {
    public static final String EXPLOIT_PREVENTER_KEY = "key.forward";
    private static final Pattern KEY_IDENTIFIER = Pattern.compile("[a-z0-9_.:-]+", Pattern.CASE_INSENSITIVE);

    private CheckHacksResponseEvaluator() { }

    /** Compatibility facade for existing callers. Transport outcomes are mapped explicitly. */
    public static DetectionStatus evaluate(ProbeDefinition probe, String response,
                                           boolean exploitPreventer, ProbeResponse.Outcome outcome) {
        return evaluateTransport(probe, response, null, exploitPreventer, outcome).status();
    }

    public static ProbeEvaluation evaluateTransport(ProbeDefinition probe, String response,
                                                    String componentIdentity, boolean exploitPreventer,
                                                    ProbeResponse.Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return switch (outcome) {
            case TIMEOUT -> result(DetectionStatus.TIMEOUT, ProbeEvidenceStrength.NONE, "response timeout");
            case DISCONNECTED, ERROR -> result(DetectionStatus.ERROR, ProbeEvidenceStrength.NONE,
                    "transport " + outcome.name().toLowerCase(Locale.ROOT));
            case UNSUPPORTED -> result(DetectionStatus.UNSUPPORTED, ProbeEvidenceStrength.NONE,
                    "transport does not support this probe");
            case CANCELLED, SKIPPED -> result(DetectionStatus.SKIPPED, ProbeEvidenceStrength.NONE,
                    "probe intentionally skipped");
            case RESPONSE -> evaluateDetailed(probe, response, componentIdentity, exploitPreventer);
        };
    }

    public static ProbeEvaluation evaluateDetailed(ProbeDefinition probe, String response,
                                                   String componentIdentity, boolean exploitPreventer) {
        Objects.requireNonNull(probe, "probe");
        String value = ProbeResponseNormalizer.normalizeText(response);
        if (value.isEmpty()) return result(DetectionStatus.CLEAN, ProbeEvidenceStrength.NONE, "empty response");

        ComponentIdentity identity = ComponentIdentity.parse(componentIdentity);
        if (identity != null) {
            String expectedType = switch (probe.mode()) {
                case METEOR, TRANSLATE -> "translate";
                case KEYBIND -> "keybind";
            };
            if (!identity.type().equals(expectedType)) {
                return result(DetectionStatus.CLEAN, ProbeEvidenceStrength.NONE, "response component type differs");
            }
            if (identity.key().equals(probe.key())) {
                return result(DetectionStatus.DETECTED, ProbeEvidenceStrength.STRONG,
                        "component identity exactly matches configured key");
            }
            return result(DetectionStatus.CLEAN, ProbeEvidenceStrength.NONE,
                    "component identity does not match configured key");
        }

        if (!probe.expectedResponse().isBlank()) {
            if (value.equals(ProbeResponseNormalizer.normalizeText(probe.expectedResponse()))) {
                return result(DetectionStatus.DETECTED, ProbeEvidenceStrength.STRONG,
                        "exact configured response matched");
            }
            if (isExactFallback(probe, value)) {
                return result(DetectionStatus.CLEAN, ProbeEvidenceStrength.NONE, "configured fallback matched");
            }
            if (value.equals(probe.key())) {
                return switch (probe.mode()) {
                    case METEOR -> result(DetectionStatus.DETECTED, ProbeEvidenceStrength.STRONG,
                            "exact configured Meteor identifier returned");
                    case TRANSLATE -> result(DetectionStatus.PROTECTED, ProbeEvidenceStrength.NONE,
                            "server-side key response was protected");
                    case KEYBIND -> exploitPreventer
                            ? result(DetectionStatus.PROTECTED, ProbeEvidenceStrength.NONE,
                            "keybind response was neutralized by the exploit preventer")
                            : result(DetectionStatus.CLEAN, ProbeEvidenceStrength.NONE,
                            "unresolved keybind identifier returned literally");
                };
            }
            if (isKeyIdentifier(value)) {
                return result(DetectionStatus.CLEAN, ProbeEvidenceStrength.NONE,
                        "response is a different identifier");
            }
            return result(DetectionStatus.CLEAN, ProbeEvidenceStrength.NONE,
                    "response did not exactly match configured evidence");
        }

        return switch (probe.mode()) {
            case METEOR -> evaluateMeteor(probe, value);
            case TRANSLATE -> evaluateTranslate(probe, value);
            case KEYBIND -> evaluateKeybind(probe, value, exploitPreventer);
        };
    }

    private static ProbeEvaluation evaluateMeteor(ProbeDefinition probe, String value) {
        if (value.equals(probe.key())) {
            return result(DetectionStatus.DETECTED, ProbeEvidenceStrength.STRONG,
                    "exact Meteor probe identifier returned");
        }
        if (isExactFallback(probe, value) || isKeyIdentifier(value)) {
            return result(DetectionStatus.CLEAN, ProbeEvidenceStrength.NONE,
                    "response does not match the configured Meteor identifier");
        }
        return result(DetectionStatus.CLEAN, ProbeEvidenceStrength.NONE, "unrelated Meteor response");
    }

    private static ProbeEvaluation evaluateTranslate(ProbeDefinition probe, String value) {
        if (isExactFallback(probe, value)) {
            return result(DetectionStatus.CLEAN, ProbeEvidenceStrength.NONE, "configured fallback matched");
        }
        if (value.equalsIgnoreCase(probe.key())) {
            return result(DetectionStatus.PROTECTED, ProbeEvidenceStrength.NONE,
                    "server-side key response was protected");
        }
        if (isKeyIdentifier(value)) {
            return result(DetectionStatus.CLEAN, ProbeEvidenceStrength.NONE,
                    "response is an unrelated translation identifier");
        }
        // The legacy sign protocol may return the client's localized translation rather
        // than its key. Record that weak evidence, but do not mark it DETECTED or confirm it.
        return result(DetectionStatus.UNCERTAIN, ProbeEvidenceStrength.WEAK,
                "localized response received without its translation identity");
    }

    private static ProbeEvaluation evaluateKeybind(ProbeDefinition probe, String value,
                                                   boolean exploitPreventer) {
        if (value.equalsIgnoreCase(probe.key())) {
            return exploitPreventer
                    ? result(DetectionStatus.PROTECTED, ProbeEvidenceStrength.NONE,
                    "keybind response was neutralized by the exploit preventer")
                    : result(DetectionStatus.CLEAN, ProbeEvidenceStrength.NONE,
                    "unresolved keybind identifier returned literally");
        }
        if (isKeyIdentifier(value)) {
            return result(DetectionStatus.CLEAN, ProbeEvidenceStrength.NONE,
                    "unrelated keybind identifier returned");
        }
        return result(DetectionStatus.UNCERTAIN, ProbeEvidenceStrength.WEAK,
                "keybind label received without its keybind identity");
    }

    private static boolean isExactFallback(ProbeDefinition probe, String value) {
        return !probe.fallback().isBlank() && value.equals(probe.fallback());
    }

    private static boolean isKeyIdentifier(String value) {
        return value.indexOf('.') >= 0 && KEY_IDENTIFIER.matcher(value).matches();
    }

    private static ProbeEvaluation result(DetectionStatus status, ProbeEvidenceStrength strength, String detail) {
        return new ProbeEvaluation(status, strength, detail);
    }

    private record ComponentIdentity(String type, String key) {
        private static ComponentIdentity parse(String identity) {
            if (identity == null) return null;
            int delimiter = identity.indexOf(':');
            if (delimiter < 1 || delimiter == identity.length() - 1) return null;
            String type = identity.substring(0, delimiter).toLowerCase(Locale.ROOT);
            if (!type.equals("translate") && !type.equals("keybind")) return null;
            return new ComponentIdentity(type, identity.substring(delimiter + 1));
        }
    }
}
