package site.vackstudio.vanticheat.detection;

import java.util.List;
import java.util.Objects;

public record DetectionResult(DetectionStatus status, String reason, List<Evidence> evidence) {
    public DetectionResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(evidence, "evidence");
        if (reason.isBlank()) throw new IllegalArgumentException("Result reason cannot be blank");
        evidence = List.copyOf(evidence);
    }

    public static DetectionResult notChecked() {
        return of(DetectionStatus.NOT_CHECKED, "not checked");
    }

    public static DetectionResult running() {
        return of(DetectionStatus.RUNNING, "running");
    }

    public static DetectionResult of(DetectionStatus status, String reason) {
        return new DetectionResult(status, reason, List.of());
    }

    public DetectionResult withEvidence(Evidence item) {
        Objects.requireNonNull(item, "item");
        java.util.ArrayList<Evidence> next = new java.util.ArrayList<>(evidence);
        next.add(item);
        return new DetectionResult(status, reason, next);
    }
}
