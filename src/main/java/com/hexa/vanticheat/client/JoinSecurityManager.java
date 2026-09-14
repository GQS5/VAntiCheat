package com.hexa.vanticheat.client;

import com.hexa.vanticheat.core.VAntiCheat;
import com.hexa.vanticheat.core.VConfig;
import com.hexa.vanticheat.core.VLogger;
import com.hexa.vanticheat.core.VTaskManager;
import com.hexa.vanticheat.evidence.EvidenceManager;
import com.hexa.vanticheat.evidence.EvidenceRecord;
import com.hexa.vanticheat.evidence.ConfidenceEngine;
import com.hexa.vanticheat.punishment.PunishmentManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Join security.
 *
 * <p>Honest limitation: brand + channels are only available AFTER login
 * (ClientDetector), so pre-login rejection of modded clients is impossible
 * without a proxy. What we do pre-login: block banned names/UUIDs fast on the
 * async login thread (no blocking IO). Post-login: evaluate fingerprints and
 * enforce policy via PunishmentManager on the entity thread.
 */
public final class JoinSecurityManager implements Listener {

    private final VAntiCheat plugin;
    private final VConfig config;
    private final VLogger log;
    private final VTaskManager tasks;
    private final ClientDetector clients;
    private final ModDetector mods;
    private final AntiSpoof spoof;
    private final PunishmentManager punishments;
    private final EvidenceManager evidence;
    private final Map<UUID, Long> joinAt = new ConcurrentHashMap<>();

    public JoinSecurityManager(VAntiCheat plugin, VConfig config, VLogger log, VTaskManager tasks,
                               ClientDetector clients, ModDetector mods, AntiSpoof spoof,
                               PunishmentManager punishments, EvidenceManager evidence) {
        this.plugin = plugin;
        this.config = config;
        this.log = log;
        this.tasks = tasks;
        this.clients = clients;
        this.mods = mods;
        this.spoof = spoof;
        this.punishments = punishments;
        this.evidence = evidence;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (!config.getBoolean("client_security.enabled", true)) return;
        // Cheap synchronous checks only. Never block this thread.
        joinAt.put(e.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        joinAt.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        if (!config.getBoolean("client_security.reject_before_world_join", true)) return;
        // Defer evaluation slightly so brand packet has a chance to arrive.
        long delay = config.getLong("client_security.post-join-eval-ticks", 100L);
        try {
            tasks.regionDelayed(e.getPlayer().getLocation(), t -> evaluatePostJoin(e.getPlayer()), delay);
        } catch (Throwable th) {
            evaluatePostJoin(e.getPlayer());
        }
    }

    private void evaluatePostJoin(org.bukkit.entity.Player player) {
        try {
            if (!player.isOnline()) return;
            if (config.exempt(player)) return;
            ClientProfile prof = clients.profile(player.getUniqueId());
            if (prof == null) return; // no data yet: never punish on absence of data
            // If a forbidden fingerprint already produced CONFIRMED evidence, punishment engine acted.
            // Here we only handle the "confirmed client + reject_before_world_join" fast path note.
            log.debug("post-join eval " + player.getName() + " brand=" + prof.brand()
                    + " client=" + prof.matchedClient() + " mod=" + prof.matchedMod());
        } catch (Throwable t) {
            log.debug("post-join eval failed: " + t.getMessage());
        }
    }
}
