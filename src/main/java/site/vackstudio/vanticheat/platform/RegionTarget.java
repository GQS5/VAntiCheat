package site.vackstudio.vanticheat.platform;

import java.util.Objects;

/** Opaque region target created by a platform adapter. */
public record RegionTarget(Object nativeWorld, int chunkX, int chunkZ) {
    public RegionTarget {
        Objects.requireNonNull(nativeWorld, "nativeWorld");
    }
}
