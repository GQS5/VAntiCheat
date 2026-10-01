package site.vackstudio.vanticheat.enforcement;

import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionStatus;

public final class ConfirmedDetection {
    private ConfirmedDetection() { }

    public static boolean isConfirmed(DetectionResult result) {
        return isConfirmed(result, true);
    }

    /**
     * A detection is confirmed when a CONFIRMATION pass also classified it DETECTED.
     *
     * <p>{@code passiveAllowed} gates detections whose support is only passive channel
     * identity. Passive evidence is authoritative about which mod is present, but the one
     * channel proven so far belongs to a legitimate utility mod, so an operator must opt in
     * before such a result becomes actionable.
     */
    public static boolean isConfirmed(DetectionResult result, boolean passiveAllowed) {
        if (result.status() != DetectionStatus.DETECTED) return false;
        boolean confirmed = result.evidence().stream().anyMatch(e ->
                "CONFIRMATION".equals(e.metadata().get("pass"))
                        && "DETECTED".equals(e.metadata().get("classification")));
        if (!confirmed || passiveAllowed) return confirmed;
        // Reject only when every supporting DETECTED record is passive-origin.
        boolean anyInteractiveSupport = result.evidence().stream()
                .filter(e -> "DETECTED".equals(e.metadata().get("classification")))
                .anyMatch(e -> !"PASSIVE".equals(e.metadata().get("transport")));
        return !anyInteractiveSupport ? false : confirmed;
    }
}
