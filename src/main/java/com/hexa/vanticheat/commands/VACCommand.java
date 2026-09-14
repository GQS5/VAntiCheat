package com.hexa.vanticheat.commands;

import com.hexa.vanticheat.antixray.MiningAnalyzer;
import com.hexa.vanticheat.antixray.OreExposureTracker;
import com.hexa.vanticheat.client.ClientDetector;
import com.hexa.vanticheat.client.ClientProfile;
import com.hexa.vanticheat.client.ModDetector;
import com.hexa.vanticheat.core.VAntiCheat;
import com.hexa.vanticheat.core.VConfig;
import com.hexa.vanticheat.core.VPermission;
import com.hexa.vanticheat.core.VProfiler;
import com.hexa.vanticheat.core.VTaskManager;
import com.hexa.vanticheat.checks.ViolationManager;
import com.hexa.vanticheat.evidence.ConfidenceEngine;
import com.hexa.vanticheat.evidence.EvidenceManager;
import com.hexa.vanticheat.evidence.EvidenceRecord;
import com.hexa.vanticheat.evidence.ViolationHistory;
import com.hexa.vanticheat.punishment.PunishmentManager;
import com.hexa.vanticheat.alerts.AlertManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** /vac command tree with tab completion. */
public final class VACCommand implements CommandExecutor, TabCompleter {

    private final VAntiCheat plugin;
    private final VConfig config;
    private final EvidenceManager evidence;
    private final ConfidenceEngine confidence;
    private final ViolationHistory history;
    private final ViolationManager violations;
    private final PunishmentManager punishments;
    private final AlertManager alerts;
    private final ClientDetector clients;
    private final ModDetector mods;
    private final OreExposureTracker oreTracker;
    private final MiningAnalyzer mining;
    private final VProfiler profiler;
    private final VTaskManager tasks;

    public VACCommand(VAntiCheat plugin, VConfig config, EvidenceManager evidence, ConfidenceEngine confidence,
                      ViolationHistory history, ViolationManager violations, PunishmentManager punishments,
                      AlertManager alerts, ClientDetector clients, ModDetector mods,
                      OreExposureTracker oreTracker, MiningAnalyzer mining, VProfiler profiler, VTaskManager tasks) {
        this.plugin = plugin;
        this.config = config;
        this.evidence = evidence;
        this.confidence = confidence;
        this.history = history;
        this.violations = violations;
        this.punishments = punishments;
        this.alerts = alerts;
        this.clients = clients;
        this.mods = mods;
        this.oreTracker = oreTracker;
        this.mining = mining;
        this.profiler = profiler;
        this.tasks = tasks;
    }

    private String msg(String key, String def) {
        try { return plugin.messages().get(key, def); } catch (Throwable t) { return def; }
    }

    private void msgc(CommandSender s, String key, String def, String... kv) {
        try {
            s.sendMessage(plugin.messages().component(key, def, kv));
        } catch (Throwable t) {
            send(s, tpl(key, def, kv));
        }
    }

    private String tpl(String key, String def, String... kv) {
        try { return plugin.messages().raw(key, def, kv); } catch (Throwable t) { return def; }
    }

    /** Grouped help menu — no internals, permission hints per group. */
    private void help(CommandSender s) {
        msgc(s, "commands.usage-header", "&6-- VAntiCheat Help --");
        msgc(s, "commands.usage-general", "&eGeneral: &f/vac status &7| &f/vac reload &7| &f/vac diagnostics");
        msgc(s, "commands.usage-security", "&eSecurity: &f/vac test <player> &7| &f/vac trust <player>");
        msgc(s, "commands.usage-inspection", "&eInspection: &f/vac inspect <player> &7| &f/vac evidence <player>");
        msgc(s, "commands.usage-debug", "&eDebug: &f/vac debug performance");
        msgc(s, "commands.usage-admin", "&eAdministration: &f/vac punish &7(info) &7| &f/vac reload");
    }

    private static String verdictKey(int conf) {
        if (conf >= 95) return "inspect.verdict-confirmed";
        if (conf >= 80) return "inspect.verdict-very-high";
        if (conf >= 60) return "inspect.verdict-high";
        if (conf >= 30) return "inspect.verdict-suspicious";
        return "inspect.verdict-normal";
    }

    private static String verdictDef(int conf) {
        if (conf >= 95) return "&4&lCONFIRMED";
        if (conf >= 80) return "&cVERY HIGH";
        if (conf >= 60) return "&6HIGH";
        if (conf >= 30) return "&eSUSPICIOUS";
        return "&aNORMAL";
    }

    private static String familyState(String evidenceText) {
        String t = evidenceText.toLowerCase();
        if (t.contains("xray") || t.contains("ore") || t.contains("reach") || t.contains("killaura")
                || t.contains("fly") || t.contains("speed")) return "high";
        if (t.contains("esp") || t.contains("spoof") || t.contains("autoclicker") || t.contains("step")) return "susp";
        return "normal";
    }

    private void familyRow(CommandSender s, String family, String state) {
        // Name as plain text (injection-safe); state fragment is code-controlled.
        var name = net.kyori.adventure.text.Component.text("  " + family + ": ",
                net.kyori.adventure.text.format.NamedTextColor.GRAY);
        var st = com.hexa.vanticheat.core.MessageFormat.render(
                switch (state) {
                    case "high" -> msg("inspect.state-high", "&cHigh");
                    case "susp" -> msg("inspect.state-suspicious", "&eSuspicious");
                    default -> msg("inspect.state-normal", "&aNormal");
                }, java.util.Map.of());
        s.sendMessage(name.append(st));
    }

    private void verdictOverview(CommandSender s, int conf, int ping) {
        var pre = net.kyori.adventure.text.Component.text("Status: ",
                net.kyori.adventure.text.format.NamedTextColor.GRAY);
        var verdict = com.hexa.vanticheat.core.MessageFormat.render(
                msg(verdictKey(conf), verdictDef(conf)), java.util.Map.of());
        var post = net.kyori.adventure.text.Component.text("  Ping: " + ping,
                net.kyori.adventure.text.format.NamedTextColor.GRAY);
        s.sendMessage(pre.append(verdict).append(post));
    }
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (!sender.hasPermission(VPermission.COMMAND) && !sender.hasPermission(VPermission.ADMIN)) {
            msgc(sender, "general.no-permission", "&c[VAC] &fNo permission.");
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.safeReload();
                msgc(sender, "commands.reloaded", "&a[VAC] &fReloaded safely (evidence + trust preserved).");
            }
            case "status" -> status(sender);
            case "diagnostics" -> diagnostics(sender);
            case "inspect" -> {
                if (!perm(sender, VPermission.INSPECT)) return true;
                Player t = target(args, 1);
                if (t == null) { msgc(sender, "general.player-not-found", "&c[VAC] &fPlayer not found."); return true; }
                inspect(sender, t);
            }
            case "evidence" -> {
                if (!perm(sender, VPermission.INSPECT)) return true;
                Player t = target(args, 1);
                if (t == null) { msgc(sender, "general.player-not-found", "&c[VAC] &fPlayer not found."); return true; }
                List<EvidenceRecord> recs = evidence.recent(t.getUniqueId(), 10);
                if (recs.isEmpty()) { msgc(sender, "commands.no-evidence", "&7[VAC] No evidence for &f{player}&7.", "player", t.getName()); return true; }
                msgc(sender, "inspect.evidence-title", "&6Recent evidence:");
                for (EvidenceRecord r : recs) {
                    msgc(sender, "inspect.evidence-row", "&7  [{severity}] &f{check}&7: {detail} &8({confidence}%)",
                            "severity", r.severity().name(), "check", r.check(), "detail", r.detection(),
                            "confidence", String.valueOf(r.confidence()));
                }
                for (String h : history.get(t.getUniqueId())) send(sender, "  " + h);
            }
            case "alerts" -> msgc(sender, "commands.alerts-hint", "&e[VAC] &fAlerts go to players with permission &e{perm}&f.", "perm", VPermission.ALERTS);
            case "trust" -> {
                if (!perm(sender, VPermission.TRUST)) return true;
                Player t = target(args, 1);
                if (t == null) { msgc(sender, "general.player-not-found", "&c[VAC] &fPlayer not found."); return true; }
                VAntiCheat.api().setTrusted(t.getUniqueId(), true);
                msgc(sender, "commands.trusted", "&a[VAC] &fTrusted &e{player}&f.", "player", t.getName());
            }
            case "untrust" -> {
                if (!perm(sender, VPermission.TRUST)) return true;
                Player t = target(args, 1);
                if (t == null) { msgc(sender, "general.player-not-found", "&c[VAC] &fPlayer not found."); return true; }
                VAntiCheat.api().setTrusted(t.getUniqueId(), false);
                msgc(sender, "commands.untrusted", "&e[VAC] &fUntrusted &e{player}&f.", "player", t.getName());
            }
            case "punish" -> {
                if (!perm(sender, VPermission.PUNISH)) return true;
                msgc(sender, "commands.punish-hint", "&e[VAC] &fPunishments are evidence-driven.");
            }
            case "test" -> {
                if (!perm(sender, VPermission.INSPECT)) return true;
                Player t = target(args, 1);
                if (t == null) { msgc(sender, "general.player-not-found", "&c[VAC] &fPlayer not found."); return true; }
                ClientProfile p = clients.profile(t.getUniqueId());
                send(sender, "brand=" + (p == null ? "unknown" : p.brand()) + " channels=" + mods.channelsOf(t.getUniqueId()));
            }
            case "debug" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("performance")) {
                    send(sender, "[VAC] " + profiler.summary());
                } else if (args.length > 2 && args[1].equalsIgnoreCase("player")) {
                    Player t = target(args, 2);
                    if (t == null) { msgc(sender, "general.player-not-found", "&c[VAC] &fPlayer not found."); return true; }
                    send(sender, "conf=" + confidence.get(t.getUniqueId()) + " ev=" + evidence.count(t.getUniqueId())
                            + " corr=" + plugin.evidence().correlatedSignals(t.getUniqueId()));
                } else if (args.length > 2 && args[1].equalsIgnoreCase("client")) {
                    Player t = target(args, 2);
                    if (t == null) { msgc(sender, "general.player-not-found", "&c[VAC] &fPlayer not found."); return true; }
                    var snap = VAntiCheat.api().getPlayerProfile(t.getUniqueId());
                    send(sender, "brand=" + snap.clientBrand() + " channels=" + snap.channels()
                            + " matched=" + orDash(snap.matchedClient()) + "/" + orDash(snap.matchedMod()));
                } else if (args.length > 2 && args[1].equalsIgnoreCase("check")) {
                    send(sender, "[VAC] check " + args[2] + ": see evidence/inspect (debug disabled by default).");
                } else {
                    Player t = target(args, 1);
                    send(sender, t == null ? "[VAC] " + profiler.summary()
                            : "conf=" + confidence.get(t.getUniqueId()) + " ev=" + evidence.count(t.getUniqueId()));
                }
            }
            default -> msgc(sender, "general.unknown-subcommand", "&c[VAC] &fUnknown subcommand. &7Use &e/vac &7for help.");
        }
        return true;
    }

    /** Colored status dashboard: modules + profile, no internals. */
    private void status(CommandSender s) {
        var snap = config.snapshot();
        msgc(s, "status.title", "&6-- VAntiCheat Status --");
        msgc(s, snap.behaviorEnabled ? "status.line-enabled" : "status.line-disabled", "&7Status: &aENABLED");
        msgc(s, "status.profile", "&7Profile: &e{profile}", "profile", snap.profile.name());
        row(s, "Client security", snap.clientSecurityEnabled);
        row(s, "Forbidden mods", snap.modlistEnabled);
        row(s, "Anti-Xray", snap.antixrayEnabled);
        row(s, "Anti-ESP", snap.antiespEnabled);
        row(s, "Behavior checks", snap.behaviorEnabled);
        msgc(s, "status.footer-hint", "&7Use &e/vac diagnostics &7for details.");
    }

    private void row(CommandSender s, String module, boolean on) {
        // State fragment is code-controlled (not user input), so parse it as template.
        String stateDef = msg(on ? "status.enabled" : "status.disabled", on ? "&aEnabled" : "&cDisabled");
        try {
            s.sendMessage(com.hexa.vanticheat.core.MessageFormat.render(
                    msg("status.row", "&7{module}: {state}").replace("{module}", module).replace("{state}", stateDef),
                    java.util.Map.of()));
        } catch (Throwable t) {
            send(s, module + ": " + (on ? "Enabled" : "Disabled"));
        }
    }

    /** Plain-language diagnostics: what works, what's limited, what to do. */
    private void diagnostics(CommandSender s) {
        try {
            msgc(s, "diagnostics.title", "&6-- VAntiCheat Diagnostics --");
            var server = plugin.serverProfile();
            msgc(s, "diagnostics.server", "&7Server: &f{platform} {mc} &7(Java {java})",
                    "platform", String.valueOf(plugin.platform()),
                    "mc", server.minecraftVersion() + " (" + server.tier() + ")",
                    "java", String.valueOf(Runtime.version().feature()));
            String via = plugin.via().available() ? "&aOK" : "&eLimited";
            String sniff = plugin.packets().mode() == com.hexa.vanticheat.integration.PacketAdapter.Mode.DISABLED
                    ? "&cMissing" : "&aOK";
            var integPre = net.kyori.adventure.text.Component.text("Integrations: ",
                    net.kyori.adventure.text.format.NamedTextColor.GRAY);
            var integMid = net.kyori.adventure.text.Component.text("ViaVersion ",
                    net.kyori.adventure.text.format.NamedTextColor.WHITE);
            var integMid2 = net.kyori.adventure.text.Component.text(", packet sniffing ",
                    net.kyori.adventure.text.format.NamedTextColor.WHITE);
            s.sendMessage(integPre.append(integMid)
                    .append(com.hexa.vanticheat.core.MessageFormat.render(via, java.util.Map.of()))
                    .append(integMid2)
                    .append(com.hexa.vanticheat.core.MessageFormat.render(sniff, java.util.Map.of())));
            if (!plugin.via().available()) {
                msgc(s, "diagnostics.recommendation-viaversion",
                        "&7Recommendation: install ViaVersion only if you need older clients; otherwise native is fastest.");
            }
            long heap = 0;
            try { heap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1024 / 1024; }
            catch (Throwable ignored) {}
            msgc(s, "diagnostics.queues", "&7Evidence: &f{evidence} &7| Heap: &f{heap}",
                    "evidence", String.valueOf(evidenceCount()),
                    "heap", heap + "MB");
            send(s, profiler.summary());
        } catch (Throwable t) {
            msgc(s, "errors.diagnostics-unavailable", "&c[VAC] &fDiagnostics unavailable.");
        }
    }

    private long evidenceCount() {
        try {
            long n = 0;
            for (Player p : Bukkit.getOnlinePlayers()) n += evidence.count(p.getUniqueId());
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Admin-friendly inspection: verdict + grouped sections, details stay in debug. */
    private void inspect(CommandSender s, Player t) {
        var snap = VAntiCheat.api().getPlayerProfile(t.getUniqueId());
        int conf = snap.confidence();
        int ping = 0;
        try { ping = t.getPing(); } catch (Throwable ignored) {}
        msgc(s, "inspect.title", "&6-- Inspection: &e{player} &6--", "player", t.getName());
        verdictOverview(s, conf, ping);
        msgc(s, "inspect.confidence", "&7Confidence: &f{confidence}%",
                "confidence", String.valueOf(conf));
        msgc(s, VAntiCheat.api().isTrusted(t.getUniqueId()) ? "inspect.trust-yes" : "inspect.trust-no",
                VAntiCheat.api().isTrusted(t.getUniqueId()) ? "&7Trusted: &ayes" : "&7Trusted: &cno");
        msgc(s, "inspect.client-title", "&6Client:");
        msgc(s, "inspect.client-brand", "&7  Brand: &f{brand}", "brand", snap.clientBrand());
        msgc(s, "inspect.client-channels", "&7  Channels: &f{channels}",
                "channels", snap.channels().isEmpty() ? "-" : String.join(", ", snap.channels()));
        String flags = orDash(snap.matchedClient());
        if (!orDash(snap.matchedMod()).equals("-")) flags += " / " + snap.matchedMod();
        msgc(s, "inspect.client-matched", "&7  Flags: &f{flags}", "flags", flags);
        msgc(s, "inspect.detection-title", "&6Detection:");
        var recs = evidence.recent(t.getUniqueId(), 10);
        String combat = "normal", movement = "normal", xray = "normal", esp = "normal";
        for (EvidenceRecord r : recs) {
            String st = familyState(r.check() + " " + r.detection());
            if (r.check().toLowerCase().contains("reach") || r.check().toLowerCase().contains("aura")
                    || r.check().toLowerCase().contains("click")) combat = maxState(combat, st);
            else if (r.check().toLowerCase().contains("fly") || r.check().toLowerCase().contains("speed")
                    || r.check().toLowerCase().contains("fall") || r.check().toLowerCase().contains("step")) movement = maxState(movement, st);
            else if (r.check().toLowerCase().contains("xray") || r.check().toLowerCase().contains("ore")) xray = maxState(xray, st);
            else esp = maxState(esp, st);
        }
        familyRow(s, "Combat", combat);
        familyRow(s, "Movement", movement);
        familyRow(s, "X-Ray", xray);
        familyRow(s, "ESP", esp);
        var stats = oreTracker.snapshot(t.getUniqueId());
        msgc(s, "inspect.ores", "&7Mining: &f{total} ores", "total", String.valueOf(stats.total),
                "hidden", String.valueOf(stats.hidden), "rare", String.valueOf(stats.rare));
        var recent = evidence.recent(t.getUniqueId(), 5);
        if (!recent.isEmpty()) {
            msgc(s, "inspect.evidence-title", "&6Recent evidence:");
            for (EvidenceRecord r : recent) {
                msgc(s, "inspect.evidence-row", "&7  [{severity}] &f{check}&7: {detail} &8({confidence}%)",
                        "severity", r.severity().name(), "check", r.check(), "detail", r.detection(),
                        "confidence", String.valueOf(r.confidence()));
            }
        }
    }

    private static String maxState(String a, String b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(String s) {
        return switch (s) { case "high" -> 2; case "susp" -> 1; default -> 0; };
    }

    private boolean perm(CommandSender s, String p) {
        if (s.hasPermission(p) || s.hasPermission(VPermission.ADMIN)) return true;
        msgc(s, "general.no-permission-detail", "&c[VAC] &fNo permission (&7{perm}&f).", "perm", p);
        return false;
    }

    private Player target(String[] args, int i) {
        if (args.length <= i) return null;
        return Bukkit.getPlayerExact(args[i]);
    }

    private static String orDash(String s) { return s == null || s.isEmpty() ? "-" : s; }

    private void send(CommandSender s, String m) {
        s.sendMessage(net.kyori.adventure.text.Component.text(m));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            List<String> subs = List.of("reload", "status", "diagnostics", "inspect", "evidence", "alerts", "trust", "untrust", "punish", "test", "debug");
            List<String> out = new ArrayList<>();
            for (String s : subs) if (s.startsWith(args[0].toLowerCase())) out.add(s);
            return out;
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("reload") && !args[0].equalsIgnoreCase("alerts")) {
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            if (args[0].equalsIgnoreCase("debug")) {
                for (String d : List.of("performance", "player", "client", "check"))
                    if (d.startsWith(args[1].toLowerCase())) out.add(d);
            }
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("debug")
                && (args[1].equalsIgnoreCase("player") || args[1].equalsIgnoreCase("client")
                    || args[1].equalsIgnoreCase("check"))) {
            if (args[1].equalsIgnoreCase("check")) return List.of("reach", "killaura", "autoclicker", "fly", "speed");
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[2].toLowerCase())) out.add(p.getName());
            return out;
        }
        return List.of();
    }
}
