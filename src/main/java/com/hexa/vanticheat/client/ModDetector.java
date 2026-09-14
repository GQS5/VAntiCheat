package com.hexa.vanticheat.client;

import com.hexa.vanticheat.core.VAntiCheat;
import com.hexa.vanticheat.core.VConfig;
import com.hexa.vanticheat.core.VLogger;
import com.hexa.vanticheat.evidence.ConfidenceEngine;
import com.hexa.vanticheat.evidence.EvidenceManager;
import com.hexa.vanticheat.evidence.EvidenceRecord;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Forbidden-mod detection via registered plugin channels with exact
 * normalized-identifier matching. Includes the complete Xaero's Minimap
 * detector (channel + fingerprint correlation + evidence + policy).
 *
 * <p>LIMITATION (documented): only mods that register an identifiable plugin
 * channel (or brand token) are visible. Minimap mods that stay silent are NOT
 * detectable by channel; they are covered by AntiESP behavior + radar policy.
 */
public final class ModDetector implements Listener {

    private final VAntiCheat plugin;
    private final VConfig config;
    private final VLogger log;
    private final EvidenceManager evidence;
    private final ConfidenceEngine confidence;
    private final ForbiddenModRegistry registry;
    /** Well-known channel namespaces mapped to mod ids (exact, curated; keys are NORMALIZED). */
    private static final Map<String, String> CHANNEL_TO_MOD = Map.ofEntries(
            Map.entry("xaerominimap", "xaeros_minimap"),
            Map.entry("xaeroworldmap", "xaeros_worldmap"),
            Map.entry("xaerominimapfair", "xaeros_minimap"),
            Map.entry("journeymap", "journeymap"),
            Map.entry("voxelmap", "voxelmap"),
            Map.entry("freecam", "freecam"),
            Map.entry("baritone", "baritone"),
            Map.entry("litematica", "litematica")
    );

    public ModDetector(VAntiCheat plugin, VConfig config, VLogger log,
                       EvidenceManager evidence, ConfidenceEngine confidence,
                       ForbiddenModRegistry registry) {
        this.plugin = plugin;
        this.config = config;
        this.log = log;
        this.evidence = evidence;
        this.confidence = confidence;
        this.registry = registry;
    }

    /** Allowlist short-circuit: sodium/lithium/iris et al never produce evidence. */
    private boolean isAllowed(String channel) {
        String n = ForbiddenModRegistry.normalize(channel);
        for (String a : FingerprintEngine.allowedByDefault()) if (n.contains(a)) return true;
        return false;
    }

    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent e) {
        ClientProfile prof = plugin.clients() == null ? null : plugin.clients().profile(e.getPlayer().getUniqueId());
        onChannel(e.getPlayer(), prof, e.getChannel());
    }

    /** Shared entry (also called from ClientDetector to avoid double handling gaps). */
    public void onChannel(Player player, ClientProfile prof, String channel) {
        try {
            if (!config.snapshot().modlistEnabled) return;
            if (isAllowed(channel)) return; // §8 allowlist
            // Ordered strategy (§9): 1 exact channel → 2 normalized id → 3 fingerprint → 4 brand corroboration.
            String modId = resolveModId(channel);
            if (modId == null) return; // never contains("xaero") alone
            ForbiddenModRegistry.ModEntry entry = registry.byId(modId);
            if (entry == null || !entry.enabled()) return;
            var policy = new ModPolicy(config);
            if (!policy.punishable(modId)) return; // detected but not forbidden → no evidence
            if (prof != null) prof.matchedMod(entry.display());
            String brand = prof == null ? "unknown" : prof.brand();
            // 4. brand corroboration = independent second signal.
            String normBrand = ForbiddenModRegistry.normalize(brand);
            boolean brandCorroborates = normBrand.contains(ForbiddenModRegistry.normalize(modId))
                    || (modId.equals("xaeros_minimap") && normBrand.contains("xaerominimap"))
                    || (modId.equals("xaeros_worldmap") && normBrand.contains("xaeroworldmap"));
            int base = (int) Math.round(65 * config.snapshot().profileMultiplier);
            int conf = confidence.addSignal(player.getUniqueId(), "forbidden_mod_channel", base);
            if (brandCorroborates) conf = confidence.addSignal(player.getUniqueId(), "behavior_confirmation", 20);

            evidence.report(EvidenceRecord.builder()
                    .player(player.getUniqueId(), player.getName())
                    .detector("ModSecurity").check("ModSecurity").detection("Forbidden mod: " + entry.display())
                    .detectionMethod("Plugin Channel").confidence(conf).violation(0)
                    .brand(brand).matched(entry.display() + " @ " + channel)
                    .values(Map.of("channel", channel, "mod", modId,
                            "primary_evidence", "plugin channel",
                            "corroboration", brandCorroborates ? "client brand" : "none",
                            "detected", "true",
                            "action", config.getString("mods." + modId + ".action", entry.action())))
                    .build());
        } catch (Throwable t) { log.debug("mod eval unavailable: " + t.getMessage()); }
    }

    /** Exact normalized matching: channel namespace or full identifier. Null = no match. */
    String resolveModId(String channel) {
        if (channel == null) return null;
        String lower = channel.toLowerCase(java.util.Locale.ROOT);
        // namespace before ':' (e.g. "xaerominimap:main").
        String ns = lower.contains(":") ? lower.substring(0, lower.indexOf(':')) : lower;
        String normNs = ForbiddenModRegistry.normalize(ns);
        if (CHANNEL_TO_MOD.containsKey(normNs)) return CHANNEL_TO_MOD.get(normNs);
        if (registry == null) return null; // fail-closed without registry, never NPE
        // Full-identifier match against registry.
        ForbiddenModRegistry.ModEntry e = registry.matchIdentifier(ns);
        if (e != null) return e.id();
        // Also try full channel string normalized.
        ForbiddenModRegistry.ModEntry e2 = registry.matchIdentifier(lower);
        return e2 == null ? null : e2.id();
    }

    public Set<String> channelsOf(java.util.UUID id) {
        ClientProfile p = plugin.clients() == null ? null : plugin.clients().profile(id);
        return p == null ? Set.of() : new HashSet<>(p.channels());
    }
}
