package site.vackstudio.vanticheat.detection.probe;

import site.vackstudio.vanticheat.detection.DetectionTarget;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ProbeRequest(UUID sessionId, DetectionTarget target, List<ProbeDefinition> probes,
                           int batchIndex, String trigger, long timeoutTicks) {
    public ProbeRequest(UUID sessionId, DetectionTarget target, List<ProbeDefinition> probes) {
        this(sessionId, target, probes, 0, "UNKNOWN", 0);
    }

    public ProbeRequest(UUID sessionId, DetectionTarget target, List<ProbeDefinition> probes,
                        int batchIndex, String trigger) {
        this(sessionId, target, probes, batchIndex, trigger, 0);
    }

    public ProbeRequest {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(probes, "probes");
        Objects.requireNonNull(trigger, "trigger");
        if (probes.isEmpty()) throw new IllegalArgumentException("Probe batch cannot be empty");
        if (batchIndex < 0) throw new IllegalArgumentException("batchIndex cannot be negative");
        if (timeoutTicks < 0) throw new IllegalArgumentException("timeoutTicks cannot be negative");
        probes = List.copyOf(probes);
    }
}
