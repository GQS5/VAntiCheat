package site.vackstudio.vanticheat.platform.paper;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import site.vackstudio.vanticheat.config.Messages;
import site.vackstudio.vanticheat.diagnostics.VAntiCheatDiagnostics;
import site.vackstudio.vanticheat.detection.DetectionStatus;
import site.vackstudio.vanticheat.detection.probe.ProbeDiagnostics;
import site.vackstudio.vanticheat.detection.probe.ProbeDefinition;
import site.vackstudio.vanticheat.detection.probe.ProbeRegistry;
import site.vackstudio.vanticheat.lunar.LunarClientService;
import site.vackstudio.vanticheat.trusted.TrustedPlayer;
import site.vackstudio.vanticheat.trusted.TrustedPlayerService;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

public final class VAntiCheatCommand implements CommandExecutor, TabCompleter {
    private final TrustedPlayerService trusted;
    private final BooleanSupplier reload;
    private final ClientProbeCommand probeCommand;
    private final LunarClientService lunar;
    private final Messages messages;
    private final VAntiCheatDiagnostics diagnostics;

    public VAntiCheatCommand(TrustedPlayerService trusted, BooleanSupplier reload,
                             ClientProbeCommand probeCommand, LunarClientService lunar,
                             Messages messages, VAntiCheatDiagnostics diagnostics) {
        this.trusted = trusted;
        this.reload = reload;
        this.probeCommand = probeCommand;
        this.lunar = lunar;
        this.messages = messages;
        this.diagnostics = diagnostics;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            if (!require(sender, "vanticheat.admin")) return true;
            help(sender);
            return true;
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (subcommand.equals("check")) {
            if (!require(sender, "vanticheat.probe")) return true;
            if (args.length < 2 || args.length > 3 || probeCommand == null) {
                sender.sendMessage(messages.render(probeCommand == null ? "probe.disabled" : "command.usage"));
                return true;
            }
            probeCommand.check(sender, args[1], args.length == 3 ? splitProbeIds(args[2]) : null);
            return true;
        }
        if (subcommand.equals("status")) {
            if (!require(sender, "vanticheat.status")) return true;
            if (args.length > 2 || args.length == 2 && !args[1].equalsIgnoreCase("verbose")) {
                sender.sendMessage(messages.render("command.usage"));
            } else {
                status(sender, args.length == 2);
            }
            return true;
        }
        if (subcommand.equals("detections")) {
            if (!require(sender, "vanticheat.status")) return true;
            detections(sender);
            return true;
        }
        if (subcommand.equals("reload")) {
            if (!require(sender, "vanticheat.reload")) return true;
            sender.sendMessage(messages.render(reload.getAsBoolean() ? "command.reloaded" : "command.reload-failed"));
            return true;
        }
        if (subcommand.equals("probes")) {
            if (!require(sender, "vanticheat.admin")) return true;
            probes(sender);
            return true;
        }
        if (subcommand.equals("probe")) {
            if (!require(sender, "vanticheat.admin")) return true;
            if (args.length != 2) sender.sendMessage(messages.render("command.usage"));
            else probe(sender, args[1]);
            return true;
        }
        if (subcommand.equals("lunar")) {
            if (!require(sender, "vanticheat.admin")) return true;
            if (args.length != 2) sender.sendMessage(messages.render("command.usage"));
            else lunarStatus(sender, args[1]);
            return true;
        }
        if (subcommand.equals("trust")) {
            if (!require(sender, "vanticheat.admin")) return true;
            trust(sender, args);
            return true;
        }
        sender.sendMessage(messages.render("command.usage"));
        return true;
    }

    private boolean require(CommandSender sender, String permission) {
        boolean playerSender = sender instanceof Player;
        if (!authorized(playerSender, playerSender && sender.hasPermission(permission))) {
            sender.sendMessage(messages.render("vanticheat.probe".equals(permission)
                    ? "command.no-permission-probe" : "command.no-permission-admin"));
            return false;
        }
        return true;
    }

    static boolean authorized(boolean playerSender, boolean hasPermission) {
        return !playerSender || hasPermission;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(messages.render("command.help.header"));
        sender.sendMessage(messages.render("command.help.client"));
        sender.sendMessage(messages.render("command.help.check"));
        sender.sendMessage(messages.render("command.help.vacprobe"));
        sender.sendMessage(messages.render("command.help.check-probe"));
        sender.sendMessage(messages.render("command.help.probes"));
        sender.sendMessage(messages.render("command.help.probe"));
        sender.sendMessage(messages.render("command.help.lunar-category"));
        sender.sendMessage(messages.render("command.help.lunar"));
        sender.sendMessage(messages.render("command.help.admin"));
        sender.sendMessage(messages.render("command.help.status"));
        sender.sendMessage(messages.render("command.help.detections"));
        sender.sendMessage(messages.render("command.help.reload"));
        sender.sendMessage(messages.render("command.help.trust-category"));
        sender.sendMessage(messages.render("command.help.trust-add"));
        sender.sendMessage(messages.render("command.help.trust-remove"));
        sender.sendMessage(messages.render("command.help.trust-list"));
    }

    private void status(CommandSender sender, boolean verbose) {
        VAntiCheatDiagnostics.Snapshot status = diagnostics.snapshot();
        VAntiCheatDiagnostics.Configuration config = status.configuration();
        sender.sendMessage("§8--- §bVAntiCheat §8---");
        sender.sendMessage("§7Status: §f" + (status.enabled() ? "ENABLED" : "DISABLED")
                + " §7Version: §f" + status.version() + " §7Runtime: §f" + status.runtime());
        sender.sendMessage("§7Health: §f" + status.health() + " §8(" + status.healthReason() + ")");
        sender.sendMessage("§7Detection: §f" + status.engineState()
                + " §7Automatic: §f" + (status.automatic().enabled() ? "ENABLED" : "DISABLED"));
        String autoCapacity = status.automatic().capacity() == 0 ? "N/A"
                : status.automatic().active() + "/" + status.automatic().capacity();
        sender.sendMessage("§7Active scans: §f" + status.activeScans()
                + " §7Automatic capacity: §f" + autoCapacity);
        sender.sendMessage("§7Trusted players: §f" + status.trustedPlayers());
        sender.sendMessage("§7Probes total/enabled: §f" + config.totalProbes() + "/" + config.enabledProbes()
                + " §7Manual/automatic: §f" + config.manualProbes() + "/" + config.automaticProbes());
        sender.sendMessage("§7Probes verified/unverified: §f" + config.verifiedProbes()
                + "/" + config.unverifiedProbes());
        sender.sendMessage("§7Floodgate/Geyser: §f" + status.platform().providers().floodgate()
                + "/" + status.platform().providers().geyser()
                + " §7Readiness: §f" + status.platform().providers().readiness());
        sender.sendMessage("§7Cached Java/Bedrock/Unknown/No-provider: §f"
                + status.platform().javaCached() + "/" + status.platform().bedrockCached() + "/"
                + status.platform().unknownCached() + "/" + status.platform().noProviderCached());
        sender.sendMessage("§7Lunar/Apollo: §f" + status.lunar().integration()
                + " §7Ready: §f" + (status.lunar().ready() ? "YES" : "NO")
                + " §7Last action: §f" + status.lunar().lastAction());
        sender.sendMessage("§7Reload: §f" + config.lastReload().state()
                + " §7Registry version: §f" + (config.registryVersion() == 0 ? "N/A" : config.registryVersion())
                + " §7Known-good active: §f" + (config.lastReload().lastKnownGoodActive() ? "YES" : "NO"));
        sender.sendMessage("§7Results detected/timeout/error: §f" + status.detections()
                + "/" + status.timeouts() + "/" + status.errors()
                + " §7Clean/protected: §f" + status.cleans() + "/" + status.protectedResults());
        if (verbose) verboseStatus(sender, status);
    }

    private void verboseStatus(CommandSender sender, VAntiCheatDiagnostics.Snapshot status) {
        VAntiCheatDiagnostics.Reload reload = status.configuration().lastReload();
        sender.sendMessage("§8--- §bVAntiCheat Details §8---");
        sender.sendMessage("§7Config loaded: §f" + status.configuration().loaded()
                + " §7Detection enabled: §f" + status.configuration().detectionEnabled());
        sender.sendMessage("§7Reload detail: §f" + reload.summary()
                + (reload.at() == null ? "" : " §7at §f" + reload.at()));
        sender.sendMessage("§7Automatic totals since startup admitted/started/slots-released/skipped/failures: §f"
                + status.automatic().admitted() + "/" + status.automatic().started() + "/"
                + status.automatic().released() + "/" + status.automatic().skippedAdmissions()
                + "/" + status.automatic().failures());
        sender.sendMessage("§7Probe outcomes since startup: §f" + status.results());
        String scanWallTime = status.completedScans() == 0 ? "N/A"
                : status.lastScanDurationMillis() + "/" + status.averageScanDurationMillis() + "ms";
        sender.sendMessage("§7Completed scans: §f" + status.completedScans()
                + " §7Runtime scan wall-time last/avg: §f" + scanWallTime
                + " §8(includes response waits; not isolated client latency)");
        sender.sendMessage("§7Scheduler transitions: §fN/A §8(no runtime transition counter)");
        if (status.activeScanDetails().isEmpty()) {
            sender.sendMessage("§7Active scan details: §fnone");
        } else {
            status.activeScanDetails().stream().limit(10).forEach(scan -> sender.sendMessage(
                    "§7Active: §f" + scan.playerName() + " §8" + scan.playerId()
                            + " §7trigger=" + scan.trigger() + " probes=" + scan.probeCount()
                            + " batch=" + (scan.batchIndex() + 1) + " pass=" + scan.pass()
                            + " current=" + scan.currentProbes() + " elapsed=" + scan.elapsedMillis() + "ms"));
        }
        List<ProbeDiagnostics.RecentScan> recent = status.recentScans().stream().limit(5).toList();
        for (ProbeDiagnostics.RecentScan scan : recent) {
            sender.sendMessage("§7Recent: §f" + scan.completedAt() + " " + scan.result() + " " + scan.playerName()
                    + " §7trigger=" + scan.trigger() + " probes=" + scan.probeCount()
                    + " elapsed=" + scan.durationMillis() + "ms");
        }
    }

    private void detections(CommandSender sender) {
        List<ProbeDiagnostics.RecentScan> detections = diagnostics.snapshot().recentScans().stream()
                .filter(scan -> scan.result() == DetectionStatus.DETECTED).limit(10).toList();
        sender.sendMessage("§8--- §bRecent Confirmed Detections §8---");
        if (detections.isEmpty()) {
            sender.sendMessage("§7No confirmed detections in the recent scan history.");
            return;
        }
        for (ProbeDiagnostics.RecentScan scan : detections) {
            sender.sendMessage("§7" + scan.completedAt() + " §f" + scan.playerName()
                    + " §8" + scan.playerId() + " §7trigger=" + scan.trigger()
                    + " probes=" + scan.detectedProbeIds());
        }
    }

    private void probes(CommandSender sender) {
        ProbeRegistry registry = diagnostics.probeRegistry();
        List<ProbeDefinition> probes = registry.all();
        sender.sendMessage("§8--- §bClient Probes §8---");
        sender.sendMessage("§7Configured: §f" + probes.size());
        sender.sendMessage("§7Enabled: §f" + registry.enabledCount());
        sender.sendMessage("§7Manual: §f" + registry.manualCount());
        sender.sendMessage("§7Automatic: §f" + registry.automaticCount());
        sender.sendMessage("§7Verified: §f" + registry.verifiedCount());
        sender.sendMessage("§7Unverified: §f" + registry.unverifiedCount());
        sender.sendMessage("§7Disabled: §f" + (probes.size() - registry.enabledCount()));
        for (ProbeDefinition probe : probes) {
            sender.sendMessage("§f" + probe.id() + " §7(" + probe.displayName() + ") mode=" + probe.mode()
                    + " category=" + probe.category() + " status=" + probeStatus(probe)
                    + " manual=" + probe.manual() + " automatic=" + probe.automatic());
        }
    }

    /** Operator-facing state: a disabled definition is never presented as merely unverified. */
    private static String probeStatus(ProbeDefinition probe) {
        if (!probe.enabled()) return "DISABLED";
        return probe.verificationStatus().name();
    }

    private void probe(CommandSender sender, String id) {
        ProbeDefinition probe = diagnostics.probeRegistry().find(id);
        if (probe == null) {
            sender.sendMessage(messages.render("probe.unknown", Map.of("probe", id)));
            return;
        }
        boolean automatic = probe.enabled() && probe.automatic();
        boolean manual = probe.enabled() && probe.manual();
        sender.sendMessage("§8--- §bProbe: " + probe.id() + " §8---");
        sender.sendMessage("§7Display name: §f" + probe.displayName());
        sender.sendMessage("§7Mode: §f" + probe.mode());
        sender.sendMessage("§7Category: §f" + probe.category());
        sender.sendMessage("§7Key: §f" + probe.key());
        sender.sendMessage("§7Expected response: §f" + (probe.expectedResponse().isBlank()
                ? "not configured" : probe.expectedResponse()));
        sender.sendMessage("§7Fallback: §f" + probe.fallback());
        sender.sendMessage("§7Enabled: §f" + probe.enabled());
        sender.sendMessage("§7Manual: §f" + probe.manual());
        sender.sendMessage("§7Automatic: §f" + probe.automatic());
        sender.sendMessage("§7Verification: §f" + probe.verificationStatus());
        sender.sendMessage("§7Status: §f" + probeStatus(probe));
        sender.sendMessage("§7Eligible automatic: §f" + automatic);
        sender.sendMessage("§7Eligible manual: §f" + manual);
        sender.sendMessage("§7Structurally valid: §fYES");
        if (!probe.notes().isBlank()) sender.sendMessage("§7Notes: §f" + probe.notes());
        if (!probe.source().isBlank()) sender.sendMessage("§7Source: §f" + probe.source());
    }

    private void lunarStatus(CommandSender sender, String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            sender.sendMessage(messages.render("lunar.offline"));
            return;
        }
        if (lunar == null) {
            sender.sendMessage(messages.render("lunar.unavailable"));
            return;
        }
        LunarClientService.LunarSnapshot snapshot = lunar.snapshot(player.getUniqueId());
        sender.sendMessage(messages.render("lunar.report.header", Map.of("player", player.getName())));
        sender.sendMessage(messages.render("lunar.report.integration", Map.of("state", snapshot.availability())));
        String support = snapshot.lunar() == null ? "UNKNOWN" : snapshot.lunar() ? "YES" : "NO";
        sender.sendMessage(messages.render("lunar.report.support", Map.of("support", support)));
        sender.sendMessage(messages.render("lunar.report.policy", Map.of("policy", snapshot.minimapPolicyEnabled() ? "ENABLED" : "DISABLED")));
        sender.sendMessage(messages.render("lunar.report.state", Map.of("state", snapshot.state())));
        sender.sendMessage(messages.render("lunar.report.action", Map.of("state", lunar.lastAction())));
    }

    private void trust(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            List<TrustedPlayer> players = trusted.list();
            sender.sendMessage(messages.render("command.trust.header", Map.of("count", players.size())));
            players.forEach(player -> sender.sendMessage(messages.render("command.trust.entry", Map.of("player", player.name()))));
            return;
        }
        if (args.length != 3 || (!args[1].equalsIgnoreCase("add") && !args[1].equalsIgnoreCase("remove"))) {
            sender.sendMessage(messages.render("command.usage"));
            return;
        }
        ResolvedPlayer player = resolve(args[2]);
        if (player == null) {
            sender.sendMessage(messages.render("command.trust.not-found"));
            return;
        }
        if (args[1].equalsIgnoreCase("add")) {
            boolean added = trusted.add(player.id(), player.name());
            trusted.save();
            sender.sendMessage(messages.render(added ? "command.trust.added" : "command.trust.exists", Map.of("player", player.name())));
        } else {
            boolean removed = trusted.remove(player.id());
            trusted.save();
            sender.sendMessage(messages.render(removed ? "command.trust.removed" : "command.trust.not-trusted", Map.of("player", player.name())));
        }
    }

    private ResolvedPlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return new ResolvedPlayer(online.getUniqueId(), online.getName());
        for (TrustedPlayer player : trusted.list()) if (player.name().equalsIgnoreCase(name)) return new ResolvedPlayer(player.id(), player.name());
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null || cached.getUniqueId() == null ? null
                : new ResolvedPlayer(cached.getUniqueId(), cached.getName() == null ? name : cached.getName());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> available = new java.util.ArrayList<>();
            if (can(sender, "vanticheat.admin")) available.addAll(List.of("help", "probes", "probe", "lunar", "trust"));
            if (can(sender, "vanticheat.status")) available.addAll(List.of("status", "detections"));
            if (can(sender, "vanticheat.reload")) available.add("reload");
            if (can(sender, "vanticheat.probe")) available.add("check");
            return matching(available, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("status") && can(sender, "vanticheat.status")) {
            return matching(List.of("verbose"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("check") && can(sender, "vanticheat.probe")) {
            return matching(onlinePlayers(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("check") && can(sender, "vanticheat.probe")) {
            return matching(probeIds(), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("probe") && can(sender, "vanticheat.admin")) {
            return matching(probeIds(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("lunar") && can(sender, "vanticheat.admin")) {
            return matching(onlinePlayers(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("trust") && can(sender, "vanticheat.admin")) {
            return matching(List.of("add", "remove", "list"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("trust") && args[1].equalsIgnoreCase("remove")
                && can(sender, "vanticheat.admin")) {
            return matching(trusted.list().stream().map(TrustedPlayer::name).toList(), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("trust") && args[1].equalsIgnoreCase("add")
                && can(sender, "vanticheat.admin")) {
            return matching(onlinePlayers(), args[2]);
        }
        return List.of();
    }

    private boolean can(CommandSender sender, String permission) {
        boolean playerSender = sender instanceof Player;
        return authorized(playerSender, playerSender && sender.hasPermission(permission));
    }

    private List<String> onlinePlayers() { return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(); }
    private List<String> probeIds() { return diagnostics.probeRegistry().all().stream().map(ProbeDefinition::id).toList(); }
    static List<String> splitProbeIds(String value) {
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(id -> !id.isEmpty()).distinct().toList();
    }
    static List<String> matching(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).distinct().sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
    }
    private record ResolvedPlayer(UUID id, String name) { }
}
