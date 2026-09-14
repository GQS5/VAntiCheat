package com.hexa.vanticheat.api;

import com.hexa.vanticheat.client.ClientDetector;
import com.hexa.vanticheat.client.ClientProfile;
import com.hexa.vanticheat.client.ModDetector;
import com.hexa.vanticheat.core.VAntiCheat;
import com.hexa.vanticheat.evidence.ConfidenceEngine;
import com.hexa.vanticheat.evidence.EvidenceManager;
import com.hexa.vanticheat.evidence.EvidenceRecord;
import com.hexa.vanticheat.evidence.ViolationHistory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Public API for HexaCore. No internal classes leak except snapshots.
 */
public final class VAntiCheatAPI {

    private final VAntiCheat plugin;
    private final ClientDetector clients;
    private final ModDetector mods;
    private final ConfidenceEngine confidence;
    private final EvidenceManager evidence;
    private final Set<UUID> trusted = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private com.hexa.vanticheat.core.TrustManager trustManager;

    public VAntiCheatAPI(VAntiCheat plugin, ClientDetector clients, ModDetector mods,
                         ConfidenceEngine confidence, EvidenceManager evidence,
                         ViolationHistory history) {
        this.plugin = plugin;
        this.clients = clients;
        this.mods = mods;
        this.confidence = confidence;
        this.evidence = evidence;
    }

    public PlayerProfileSnapshot getPlayerProfile(UUID uuid) {
        ClientProfile p = clients == null ? null : clients.profile(uuid);
        String name = org.bukkit.Bukkit.getPlayer(uuid) != null ? org.bukkit.Bukkit.getPlayer(uuid).getName() : "?";
        return new PlayerProfileSnapshot(uuid, name,
                p == null ? "unknown" : p.brand(),
                p == null ? Set.of() : new HashSet<>(p.channels()),
                p == null ? "" : p.matchedClient(),
                p == null ? "" : p.matchedMod(),
                confidence == null ? 0 : confidence.get(uuid));
    }

    public int getConfidence(UUID uuid) { return confidence == null ? 0 : confidence.get(uuid); }
    public List<EvidenceRecord> getEvidence(UUID uuid) { return getEvidence(uuid, 20); }
    public List<EvidenceRecord> getEvidence(UUID uuid, int limit) {
        return evidence == null ? List.of() : evidence.recent(uuid, limit);
    }
    public boolean isTrusted(UUID uuid) { return trusted.contains(uuid); }
    public void setTrusted(UUID uuid, boolean v) {
        if (v) trusted.add(uuid); else trusted.remove(uuid);
        if (trustManager != null) trustManager.setTrusted(uuid, v);
    }
    public void setTrustManager(com.hexa.vanticheat.core.TrustManager tm) { this.trustManager = tm; }
    public boolean bypasses(UUID uuid, String category) {
        if (trustManager != null) return trustManager.bypasses(uuid, category);
        return false;
    }
    public boolean isDetected(UUID uuid) { return getConfidence(uuid) >= 60; }
}
