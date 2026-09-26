package site.vackstudio.vanticheat.detection.probe;

import site.vackstudio.vanticheat.detection.DetectionTarget;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ProbeRequest(UUID sessionId, DetectionTarget target, List<ProbeDefinition> probes) {
    public ProbeRequest {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(probes, "probes");
        if (probes.isEmpty()) throw new IllegalArgumentException("Probe batch cannot be empty");
        probes = List.copyOf(probes);
    }
}
