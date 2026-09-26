package site.vackstudio.vanticheat.enforcement;

import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionStatus;

public final class ConfirmedDetection {
    private ConfirmedDetection() { }

    public static boolean isConfirmed(DetectionResult result) {
        if (result.status() != DetectionStatus.DETECTED) return false;
        return result.evidence().stream().anyMatch(e ->
                "CONFIRMATION".equals(e.metadata().get("pass"))
                        && "DETECTED".equals(e.metadata().get("classification")));
    }
}
