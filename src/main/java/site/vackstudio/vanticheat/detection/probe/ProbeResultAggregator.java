package site.vackstudio.vanticheat.detection.probe;

import site.vackstudio.vanticheat.detection.DetectionStatus;

import java.util.List;
import java.util.Objects;

/** Shared session-level precedence rules for a set of per-probe results. */
public final class ProbeResultAggregator {
    private ProbeResultAggregator() { }

    public static DetectionStatus aggregate(List<DetectionStatus> statuses) {
        Objects.requireNonNull(statuses, "statuses");
        if (statuses.contains(DetectionStatus.DETECTED)) return DetectionStatus.DETECTED;
        if (statuses.contains(DetectionStatus.PROTECTED)) return DetectionStatus.PROTECTED;
        if (statuses.contains(DetectionStatus.ERROR)) return DetectionStatus.ERROR;
        if (statuses.contains(DetectionStatus.TIMEOUT)) return DetectionStatus.TIMEOUT;
        if (statuses.contains(DetectionStatus.UNSUPPORTED)) return DetectionStatus.UNSUPPORTED;
        if (statuses.contains(DetectionStatus.UNCERTAIN)) return DetectionStatus.UNCERTAIN;
        if (statuses.contains(DetectionStatus.CLEAN)) return DetectionStatus.CLEAN;
        return DetectionStatus.SKIPPED;
    }

    public static boolean needsConfirmation(DetectionStatus status) {
        return status == DetectionStatus.DETECTED || status == DetectionStatus.PROTECTED;
    }
}
