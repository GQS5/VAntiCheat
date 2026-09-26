package site.vackstudio.vanticheat.detection.probe;

import site.vackstudio.vanticheat.detection.DetectionStatus;

import java.util.Locale;

/** Preserves the CheckHacks 1.3.1 response branches without its plugin classes. */
public final class CheckHacksResponseEvaluator {
    public static final String EXPLOIT_PREVENTER_KEY = "key.forward";

    private CheckHacksResponseEvaluator() { }

    public static DetectionStatus evaluate(ProbeDefinition probe, String response,
                                           boolean exploitPreventer, ProbeResponse.Outcome outcome) {
        if (outcome == ProbeResponse.Outcome.TIMEOUT) return DetectionStatus.PROTECTED;
        if (outcome != ProbeResponse.Outcome.RESPONSE) return DetectionStatus.ERROR;

        String value = response == null ? "" : response.strip();
        if (value.isEmpty()) return DetectionStatus.CLEAN;

        String lowerKey = probe.key().toLowerCase(Locale.ROOT);
        if (value.length() == lowerKey.length() + 1
                && value.regionMatches(true, 0, lowerKey, 0, lowerKey.length())
                && Character.isLetter(value.charAt(lowerKey.length()))) {
            return DetectionStatus.CLEAN;
        }

        return switch (probe.mode()) {
            case METEOR -> {
                if (value.equalsIgnoreCase(probe.key())) yield DetectionStatus.DETECTED;
                if (startsWithIgnoreCase(value, probe.fallback())) yield DetectionStatus.CLEAN;
                yield DetectionStatus.DETECTED;
            }
            case TRANSLATE -> {
                if (startsWithIgnoreCase(value, probe.fallback())) yield DetectionStatus.CLEAN;
                if (value.equalsIgnoreCase(probe.key())) yield DetectionStatus.PROTECTED;
                yield DetectionStatus.DETECTED;
            }
            case KEYBIND -> {
                if (exploitPreventer && value.equalsIgnoreCase(probe.key())) yield DetectionStatus.PROTECTED;
                if (value.equalsIgnoreCase(probe.key())) yield DetectionStatus.CLEAN;
                yield DetectionStatus.DETECTED;
            }
        };
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return prefix.length() <= value.length()
                && value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
