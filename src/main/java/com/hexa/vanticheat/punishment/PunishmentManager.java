package com.hexa.vanticheat.punishment;

import com.hexa.vanticheat.alerts.AlertManager;
import com.hexa.vanticheat.core.VAntiCheat;
import com.hexa.vanticheat.core.VConfig;
import com.hexa.vanticheat.core.VLogger;
import com.hexa.vanticheat.core.VTaskManager;
import com.hexa.vanticheat.evidence.EvidenceManager;
import com.hexa.vanticheat.evidence.EvidenceRecord;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single place where enforcement happens. All server-mutating work is
 * dispatched to the player's entity scheduler (Folia-safe).
 */
public final class PunishmentManager {

    private final VAntiCheat plugin;
    private final VConfig config;
    private final VLogger log;
    private final VTaskManager tasks;
    private final EvidenceManager evidence;
    private final AlertManager alerts;
    private final PunishmentPolicy policy;
    private final Map<UUID, Long> lastActionAt = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 5_000;

    public PunishmentManager(VAntiCheat plugin, VConfig config, VLogger log, VTaskManager tasks,
                             EvidenceManager evidence, AlertManager alerts) {
        this.plugin = plugin;
        this.config = config;
        this.log = log;
        this.tasks = tasks;
        this.evidence = evidence;
        this.alerts = alerts;
        this.policy = new PunishmentPolicy(config);
    }

    public PunishmentPolicy policy() { return policy; }

    /** Entry from EvidenceManager. Never throws. */
    public void onEvidence(EvidenceRecord record) {
        if (!config.snapshot().punishmentEnabled) return;
        if (record.playerId() == null) return;
        try {
            String category = mapCategory(record);
            Action configured = policy.actionFor(category, defaultFor(record));
            Action gated = policy.gateByConfidence(configured, record.confidence());
            if (gated == Action.NONE) return;
            // Punishment gates (§17): KICK needs confirmed/severe; TEMPBAN/BAN need diversity.
            if (!gateAllowed(record, category, gated)) return;
            if (config.getBoolean("qa.punishment.dry-run", false) && gated != Action.ALERT && gated != Action.WARN) {
                // Safe QA mode: log what WOULD happen, only alert in game.
                log.info("[DRY-RUN] would have applied " + gated + " to " + record.playerName()
                        + " (" + record.detection() + " conf=" + record.confidence() + ")");
                alerts.dispatch(record, Action.ALERT);
                return;
            }
            // Cooldown per player to avoid action spam.
            long now = System.currentTimeMillis();
            Long last = lastActionAt.get(record.playerId());
            if (last != null && now - last < COOLDOWN_MS && gated != Action.BAN) return;
            lastActionAt.put(record.playerId(), now);
            enforce(record, category, gated);
        } catch (Throwable t) {
            log.debug("punishment eval failed: " + t.getMessage());
        }
    }

    private String mapCategory(EvidenceRecord r) {
        String d = (r.check() + " " + r.detection()).toLowerCase();        if (d.contains("client") || d.contains("spoof")) return "forbidden_client";
        if (d.contains("mod") || d.contains("xaero") || d.contains("journeymap") || d.contains("voxel")
                || d.contains("freecam") || d.contains("baritone") || d.contains("litematica")) return "forbidden_mod";
        if (d.contains("xray") || d.contains("ore")) return "confirmed_xray";
        if (d.contains("esp") || d.contains("radar")) return "esp";
        return "behavior";
    }

    private Action defaultFor(EvidenceRecord r) {
        if (r.confidence() >= 95) return Action.KICK;
        if (r.confidence() >= 80) return Action.WARN;
        return Action.ALERT;
    }

    /** Spec §17 gates: one weak check never → BAN; severe needs diversity or confirmed client. */
    private boolean gateAllowed(EvidenceRecord r, String category, Action gated) {
        if (gated == Action.BAN || gated == Action.TEMPBAN) {
            boolean confirmedClient = category.equals("forbidden_client") && r.confidence() >= 95;
            if (confirmedClient) return true;
            int diversity = 1;
            try {
                diversity = plugin.evidence().correlation().diversity(r.playerId());
            } catch (Throwable ignored) {}
            // Correlated signals attached to this record count too.
            if (r.correlatedSignals().size() >= 2) diversity = Math.max(diversity, r.correlatedSignals().size());
            if (!policy.diversityAllows(gated, diversity)) {
                log.debug("punishment diversity gate blocked " + gated + " for " + r.playerName());
                return false;
            }
            if (r.confidence() < 80) return false;
        }
        if (gated == Action.KICK && r.confidence() < 80
                && !category.equals("forbidden_client") && !category.equals("forbidden_mod")) return false;
        return true;
    }

    private void enforce(EvidenceRecord record, String category, Action action) {
        Player player = Bukkit.getPlayer(record.playerId());
        alerts.dispatch(record, action);
        if (player == null || !player.isOnline()) {
            if (action == Action.BAN || action == Action.TEMPBAN) applyBan(record, action, null);
            return;
        }
        if (config.exempt(player) && action != Action.ALERT) {
            log.debug("exempt player, suppressing " + action + " for " + player.getName());
            return;
        }
        switch (action) {
            case ALERT -> { /* alerts already dispatched */ }
            case WARN -> {
                String w = msg("warn-message", "[VAC] Warning: suspicious activity detected ({check}).")
                        .replace("{check}", record.detection());
                final String warn = w;
                tasks.entity(player, t -> player.sendMessage(
                        net.kyori.adventure.text.Component.text(warn)));
            }
            case KICK -> {
                String reason = kickReason(record);
                tasks.kick(player, reason);
                log.info("Kicked " + record.playerName() + " (" + record.detection() + " conf=" + record.confidence() + ")");
            }
            case TEMPBAN, BAN -> applyBan(record, action, player);
            default -> { }
        }
    }

    private void applyBan(EvidenceRecord record, Action action, Player online) {
        tasks.async(() -> {
            try {
                String reason = "VAntiCheat: " + record.detection() + " (confidence " + record.confidence() + "%)";
                Date expires = null;
                if (action == Action.TEMPBAN) expires = parseDuration(policy.durationFor(mapCategory(record), "1d"));
                // Must run ban-list mutation carefully; Bukkit ban list is thread-safe in practice, kick on entity thread.
                final Date exp = expires;
                Bukkit.getBanList(BanList.Type.NAME).addBan(record.playerName(), reason, exp, "VAntiCheat");
                if (online != null) tasks.kick(online, reason);
                log.info("Banned " + record.playerName() + " (" + action + "): " + reason);
            } catch (Throwable t) {
                log.warn("ban failed: " + t.getMessage());
            }
        });
    }

    private String kickReason(EvidenceRecord r) {
        String cat = mapCategory(r);
        if (cat.equals("forbidden_client"))
            return msg("kick-forbidden-client", "Kicked by VAntiCheat\nForbidden client detected.\nAppeal: contact staff");
        if (cat.equals("forbidden_mod"))
            return msg("kick-forbidden-mod", "Kicked by VAntiCheat\nForbidden mod detected.\nAppeal: contact staff")
                    .replace("{mod}", r.matchedIdentifier());
        return msg("kick-cheat", "Kicked by VAntiCheat\n{reason}\nConfidence: {confidence}%\nAppeal: contact staff")
                .replace("{reason}", r.detection())
                .replace("{confidence}", String.valueOf(r.confidence()));
    }

    private String msg(String key, String def) {
        try { return plugin.messages().get(key, def); } catch (Throwable t) { return def; }
    }

    /** Parses "30m", "2h", "1d", "7d". Defaults to 1d. */
    static Date parseDuration(String s) {
        try {
            long mult = 24L * 3600_000L;
            if (s.endsWith("m")) mult = 60_000L;
            else if (s.endsWith("h")) mult = 3600_000L;
            else if (s.endsWith("d")) mult = 24L * 3600_000L;
            String num = s.replaceAll("[^0-9]", "");
            long n = Long.parseLong(num.isEmpty() ? "1" : num);
            return new Date(System.currentTimeMillis() + n * mult);
        } catch (Exception e) {
            return new Date(System.currentTimeMillis() + 24L * 3600_000L);
        }
    }
}
