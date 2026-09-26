package site.vackstudio.vanticheat.detection;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record Evidence(
        EvidenceType type,
        String source,
        Instant timestamp,
        Map<String, String> metadata) {
    public Evidence {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(metadata, "metadata");
        if (source.isBlank()) throw new IllegalArgumentException("Evidence source cannot be blank");
        metadata = Map.copyOf(metadata);
    }
}
