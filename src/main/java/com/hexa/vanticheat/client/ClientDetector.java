package com.hexa.vanticheat.client;

import com.hexa.vanticheat.core.VAntiCheat;
import com.hexa.vanticheat.core.VConfig;
import com.hexa.vanticheat.core.VLogger;
import com.hexa.vanticheat.core.VTaskManager;
import com.hexa.vanticheat.evidence.ConfidenceEngine;
import com.hexa.vanticheat.evidence.EvidenceManager;
import com.hexa.vanticheat.evidence.EvidenceRecord;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client identification: brand channel + registered plugin channels.
 * Implements PluginMessageListener for minecraft:brand / MC|Brand payloads.
 */
public final class ClientDetector implements Listener, PluginMessageListener {

    private final VAntiCheat plugin;
    private final VConfig config;
    private final VLogger log;
    private final EvidenceManager evidence;
    private final ConfidenceEngine confidence;
    private final VTaskManager tasks;
    private final Map<UUID, ClientProfile> profiles = new ConcurrentHashMap<>();

    public ClientDetector(VAntiCheat plugin, VConfig config, VLogger log,
                          EvidenceManager evidence, ConfidenceEngine confidence, VTaskManager tasks) {
        this.plugin = plugin;
        this.config = config;
        this.log = log;
        this.evidence = evidence;
        this.confidence = confidence;
        this.tasks = tasks;
    }

    public ClientProfile profile(UUID id) { return profiles.get(id); }
    public String brandOf(UUID id) {
        ClientProfile p = profiles.get(id);
        return p == null ? "unknown" : p.brand();
    }

    // ---- brand packet ----

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!channel.equals("minecraft:brand") && !channel.equals("MC|Brand")) return;
        String brand = decodeBrand(message);
        ClientProfile prof = profiles.computeIfAbsent(player.getUniqueId(), k -> new ClientProfile(k, player.getName()));
        prof.brand(brand);
        log.debug("brand: " + player.getName() + " -> " + brand);
        evaluateBrand(player, prof, brand);
    }

    static String decodeBrand(byte[] msg) {
        try {
            // Modern minecraft:brand payload is a VarInt-prefixed UTF-8 string; legacy MC|Brand same.
            int[] pos = {0};
            int len = readVarInt(msg, pos);
            if (len < 0 || len > 64 || pos[0] + len > msg.length) {
                return new String(msg, StandardCharsets.UTF_8).trim();
            }
            return new String(msg, pos[0], len, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            try { return new String(msg, StandardCharsets.UTF_8).trim(); } catch (Exception ex) { return "unknown"; }
        }
    }

    private static int readVarInt(byte[] b, int[] pos) {
        int num = 0, shift = 0;
        while (pos[0] < b.length && shift < 35) {
            byte cur = b[pos[0]++];
            num |= (cur & 0x7F) << shift;
            if ((cur & 0x80) == 0) return num;
            shift += 7;
        }
        return -1;
    }

    // ---- channel registration ----

    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent e) {
        ClientProfile prof = profiles.computeIfAbsent(e.getPlayer().getUniqueId(),
                k -> new ClientProfile(k, e.getPlayer().getName()));
        prof.channels().add(e.getChannel());
        evaluateChannel(e.getPlayer(), prof, e.getChannel());
        // Cross-feed mod detector (same channel stream).
        if (plugin.mods() != null) plugin.mods().onChannel(e.getPlayer(), prof, e.getChannel());
        if (plugin.spoof() != null) plugin.spoof().onUpdate(e.getPlayer(), prof);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        profiles.computeIfAbsent(e.getPlayer().getUniqueId(),
                k -> new ClientProfile(k, e.getPlayer().getName())).playerName(e.getPlayer().getName());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // Keep profile briefly for /vac evidence after logout? Drop to bound memory.
        profiles.remove(e.getPlayer().getUniqueId());
    }

    // ---- evaluation ----

    private void evaluateBrand(Player player, ClientProfile prof, String brand) {
        try {
            if (!config.snapshot().clientSecurityEnabled) return;
            var res = ClientFingerprint.matchBrandDetailed(brand);
            if (res.isEmpty()) return;
            var r = res.get();
            KnownClients.Fingerprint f = r.fingerprint();
            if (!config.getBoolean("clients." + f.id() + ".enabled", true)) return;
            prof.matchedClient(f.display());
            // Only STRONG fingerprints get full weight; medium = corroboration only.
            int w = r.strength() == ClientFingerprint.Strength.STRONG
                    ? KnownClients.DEFAULT_WEIGHTS.getOrDefault(f.id(), 70) : 10;
            w = (int) Math.round(w * config.profile().weightMultiplier());
            int conf = confidence.addSignal(player.getUniqueId(), "known_forbidden_client_fingerprint", w);
            evidence.report(EvidenceRecord.builder()
                    .player(player.getUniqueId(), player.getName())
                    .detector("ClientSecurity").check("ClientSecurity").detection("Forbidden client: " + f.display())
                    .detectionMethod("Client Brand").confidence(conf).violation(0)
                    .brand(brand).matched(f.display())
                    .values(Map.of("brand", brand, "strength", r.strength().name(),
                            "action", config.getString("clients." + f.id() + ".action", "kick")))
                    .build());
        } catch (Throwable t) { log.debug("brand eval unavailable: " + t.getMessage()); }
    }

    private void evaluateChannel(Player player, ClientProfile prof, String channel) {
        try {
            if (!config.snapshot().clientSecurityEnabled) return;
            var res = ClientFingerprint.matchChannelDetailed(channel, prof.brand());
            if (res.isEmpty()) return;
            var r = res.get();
            KnownClients.Fingerprint f = r.fingerprint();
            if (!config.getBoolean("clients." + f.id() + ".enabled", true)) return;
            prof.matchedClient(f.display());
            int conf = confidence.addSignal(player.getUniqueId(), "known_plugin_channel", 20);
            evidence.report(EvidenceRecord.builder()
                    .player(player.getUniqueId(), player.getName())
                    .detector("ClientSecurity").check("ClientSecurity").detection("Forbidden client channel: " + f.display())
                    .detectionMethod("Plugin Channel").confidence(conf).violation(0)
                    .brand(prof.brand()).matched(f.display() + " @ " + channel)
                    .values(Map.of("channel", channel, "strength", r.strength().name()))
                    .build());
        } catch (Throwable t) { log.debug("channel eval unavailable: " + t.getMessage()); }
    }

    public Map<UUID, ClientProfile> allProfiles() { return profiles; }
}
