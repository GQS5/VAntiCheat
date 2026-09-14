package com.hexa.vanticheat.integration;

import com.hexa.vanticheat.version.ClientVersionProfile;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ViaVersion adapter (§7/§31): optional, reflective, no hard dependency.
 * Resolves protocol once per player, caches immutable profile, degrades to
 * unknown-native when absent. Never queried per event.
 */
public final class ViaVersionAdapter {
    private final boolean available;
    private final Map<UUID, ClientVersionProfile> cache = new ConcurrentHashMap<>();

    public ViaVersionAdapter() {
        boolean ok;
        try { Class.forName("com.viaversion.viaversion.api.Via"); ok = true; }
        catch (Throwable t) { ok = false; }
        this.available = ok;
    }

    public boolean available() { return available; }

    public ClientVersionProfile profile(UUID player) {
        return cache.computeIfAbsent(player, id -> {
            if (!available) return ClientVersionProfile.unknownNative(id);
            try {
                // Reflective only; any failure → graceful unknown.
                Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
                var m = via.getMethod("getAPI");
                Object api = m.invoke(null);
                var pm = api.getClass().getMethod("getPlayerVersion", UUID.class);
                int v = (int) pm.invoke(api, id);
                return new ClientVersionProfile(id, v, false, true);
            } catch (Throwable t) {
                return ClientVersionProfile.unknownNative(id);
            }
        });
    }

    public void purge(UUID id) { cache.remove(id); }
}
