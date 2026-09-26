package site.vackstudio.vanticheat.detection.behavior;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ViolationSignal(String detectorId, UUID playerId, long timestampNanos,
                              SignalLevel level, String reason, Map<String, String> evidence) {
    public ViolationSignal {
        Objects.requireNonNull(detectorId, "detectorId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(reason, "reason");
        evidence = Map.copyOf(evidence == null ? Map.of() : evidence);
    }
}
