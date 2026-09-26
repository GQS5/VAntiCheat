package site.vackstudio.vanticheat.platform;

import java.util.Objects;

/** Opaque entity target created by a platform adapter. */
public record EntityTarget(Object nativeEntity) {
    public EntityTarget {
        Objects.requireNonNull(nativeEntity, "nativeEntity");
    }
}
