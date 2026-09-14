package com.hexa.vanticheat.version;

import java.util.UUID;

/**
 * Per-player immutable client version view (§7). Cached; never query external
 * APIs per event. Absent ViaVersion → UNKNOWN_NATIVE (graceful, native rules).
 */
public record ClientVersionProfile(UUID player, int protocol, boolean nativeClient, boolean viaCompat) {
    public static ClientVersionProfile unknownNative(UUID id) {
        return new ClientVersionProfile(id, -1, true, false);
    }

    public boolean fastPath(ServerProfile server) {
        return nativeClient && server.nativeFastPath();
    }
}
