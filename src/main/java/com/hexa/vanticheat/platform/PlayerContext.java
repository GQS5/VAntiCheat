package com.hexa.vanticheat.platform;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Minimal player view for the core engine (§3). Snapshot primitives on the
 * region/entity thread; never leak Player into async work — convert and drop.
 */
public record PlayerContext(UUID id, String name, int ping, GameMode mode,
                             boolean gliding, boolean inVehicle, boolean allowFlight) {
    public static PlayerContext snapshot(Player p) {
        UUID id = p.getUniqueId();
        String name = "?";
        int ping = 0;
        GameMode mode = GameMode.SURVIVAL;
        boolean gl = false, veh = false, fly = false;
        try { name = p.getName(); } catch (Throwable ignored) {}
        try { ping = p.getPing(); } catch (Throwable ignored) {}
        try { mode = p.getGameMode(); } catch (Throwable ignored) {}
        try { gl = p.isGliding(); } catch (Throwable ignored) {}
        try { veh = p.isInsideVehicle(); } catch (Throwable ignored) {}
        try { fly = p.getAllowFlight(); } catch (Throwable ignored) {}
        return new PlayerContext(id, name, ping, mode, gl, veh, fly);
    }
}
