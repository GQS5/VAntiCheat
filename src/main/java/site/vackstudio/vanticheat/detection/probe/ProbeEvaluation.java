package site.vackstudio.vanticheat.detection.probe;

import site.vackstudio.vanticheat.detection.DetectionStatus;

import java.util.Objects;

public record ProbeEvaluation(DetectionStatus status, ProbeEvidenceStrength evidenceStrength, String detail) {
    public ProbeEvaluation {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(evidenceStrength, "evidenceStrength");
        detail = detail == null ? "" : detail;
    }
}
