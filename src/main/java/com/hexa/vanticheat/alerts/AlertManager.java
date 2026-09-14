package com.hexa.vanticheat.alerts;

import com.hexa.vanticheat.core.VAntiCheat;
import com.hexa.vanticheat.core.VConfig;
import com.hexa.vanticheat.core.VPermission;
import com.hexa.vanticheat.evidence.EvidenceRecord;
import com.hexa.vanticheat.punishment.Action;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Sends admin alerts to permission holders + console. Thread-safe (Bukkit broadcast is safe). */
public final class AlertManager {

    private final VAntiCheat plugin;
    private final VConfig config;

    public AlertManager(VAntiCheat plugin, VConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void dispatch(EvidenceRecord record, Action action) {
        if (!config.snapshot().alertsEnabled) return;
        int minConf = config.getInt("alerts.min-confidence", 30);
        if (record.confidence() < minConf && action == Action.ALERT) return;
        net.kyori.adventure.text.Component msg;
        try {
            var messages = plugin.messages();
            if (record.confidence() >= 95 && !record.matchedIdentifier().isEmpty()) {
                msg = messages.component("alerts.confirmed", "&4[VAC] CRITICAL &8| &e{player} &8| &f{mod} &8| &7{confidence}% &8| &c{action}",
                        "player", record.playerName(), "mod", record.matchedIdentifier(),
                        "confidence", String.valueOf(record.confidence()),
                        "action", record.values().getOrDefault("action", action.name()));
            } else {
                var sev = com.hexa.vanticheat.core.MessageFormat.render(
                        severityTag(record.severity()) + " " + record.severity().name(),
                        java.util.Map.of());
                var rest = messages.component("alerts.format-rest", "&8| &e{player} &8| &f{check} &8| &7{confidence}% &8| &f{action}",
                        "player", record.playerName(), "check", record.check(),
                        "confidence", String.valueOf(record.confidence()), "action", action.name());
                msg = net.kyori.adventure.text.Component.text("[VAC] ",
                                net.kyori.adventure.text.format.NamedTextColor.GRAY)
                        .append(sev).append(net.kyori.adventure.text.Component.space()).append(rest);
            }
        } catch (Throwable t) {
            msg = net.kyori.adventure.text.Component.text(
                    AlertFormatter.generic(record, action.name()));
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                if (p.hasPermission(VPermission.ALERTS) || p.hasPermission(VPermission.ADMIN)) {
                    p.sendMessage(msg);
                }
            } catch (Throwable ignored) {
            }
        }
        plugin.getLogger().info("[VAC] " + record.severity() + " | " + record.playerName()
                + " | " + record.check() + " | " + record.confidence() + "% | " + action);
    }

    private static String severityTag(com.hexa.vanticheat.evidence.Severity s) {
        return switch (s) {
            case CRITICAL -> "&4&lCRITICAL&r";
            case HIGH -> "&cHIGH";
            case MEDIUM -> "&eMEDIUM";
            case LOW -> "&aLOW";
            default -> "&7INFO";
        };
    }
}
