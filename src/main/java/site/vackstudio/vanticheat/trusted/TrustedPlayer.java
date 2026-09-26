package site.vackstudio.vanticheat.trusted;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TrustedPlayer(UUID id, String name, Instant addedAt) {
    public TrustedPlayer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(addedAt, "addedAt");
        if (name.isBlank()) throw new IllegalArgumentException("name cannot be blank");
    }
}
