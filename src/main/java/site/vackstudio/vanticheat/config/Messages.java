package site.vackstudio.vanticheat.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Messages {
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("prefix", "&8[&bVAntiCheat&8] &r"),
            Map.entry("command.no-permission-admin", "%prefix%You do not have permission to access VAntiCheat administration."),
            Map.entry("command.no-permission-probe", "%prefix%You do not have permission to run client probes."),
            Map.entry("command.usage", "%prefix%Usage: /vac help"),
            Map.entry("command.reloaded", "%prefix%Configuration reloaded."),
            Map.entry("command.reload-failed", "%prefix%Reload failed; last known-good configuration remains active."),
            Map.entry("command.help.header", "%prefix%Commands:"),
            Map.entry("command.help.client", "%prefix%CLIENT DETECTION"),
            Map.entry("command.help.reload", "%prefix%/vac reload - atomically reload client probe configuration"),
            Map.entry("command.help.status", "%prefix%/vac status [verbose] - show runtime health and diagnostics"),
            Map.entry("command.help.detections", "%prefix%/vac detections - list recent confirmed detections"),
            Map.entry("command.help.probes", "%prefix%/vac probes - list configured client probes"),
            Map.entry("command.help.probe", "%prefix%/vac probe <probe-id> - inspect a client probe"),
            Map.entry("command.help.check-probe", "%prefix%/vac check <player> <probe-id[,probe-id...]> - run selected probes"),
            Map.entry("command.help.lunar-category", "%prefix%LUNAR"),
            Map.entry("command.help.admin", "%prefix%ADMIN"),
            Map.entry("command.help.trust-category", "%prefix%TRUST"),
            Map.entry("command.help.trust", "%prefix%/vac trust <player> - trust a player"),
            Map.entry("command.help.trust-add", "%prefix%/vac trust add <player> - trust a player"),
            Map.entry("command.help.trust-remove", "%prefix%/vac trust remove <player> - remove trust"),
            Map.entry("command.help.trust-list", "%prefix%/vac trust list - list trusted players"),
            Map.entry("command.help.check", "%prefix%/vac check <player> - run a client probe"),
            Map.entry("command.help.vacprobe", "%prefix%/vacprobe <player> - run configured manual probes"),
            Map.entry("command.help.lunar", "%prefix%/vac lunar <player> - show Lunar Client policy state"),
            Map.entry("command.trust.not-found", "%prefix%Player is not online or cached by the server."),
            Map.entry("command.trust.added", "%prefix%%player% is now trusted."),
            Map.entry("command.trust.exists", "%prefix%%player% is already trusted."),
            Map.entry("command.trust.removed", "%prefix%%player% is no longer trusted."),
            Map.entry("command.trust.not-trusted", "%prefix%%player% is not trusted."),
            Map.entry("command.trust.empty", "%prefix%No trusted players."),
            Map.entry("command.trust.header", "%prefix%Trusted players (%count%):"),
            Map.entry("command.trust.entry", "%prefix%- %player%"),
            Map.entry("probe.usage", "%prefix%Usage: /vacprobe <player>"),
            Map.entry("probe.offline", "%prefix%Player is not online"),
            Map.entry("probe.bedrock-skipped", "%prefix%Client probes are disabled for Bedrock players."),
            Map.entry("probe.platform-pending", "%prefix%Client platform is still being identified; try again shortly."),
            Map.entry("probe.trusted-skipped", "%prefix%Client probes are disabled for trusted players."),
            Map.entry("probe.disabled", "%prefix%Client detection is unavailable; check the server log for the configuration error."),
            Map.entry("probe.start", "%prefix%Starting client probe for %player%"),
            Map.entry("probe.unknown", "%prefix%Unknown client probe: %probe%"),
            Map.entry("probe.disabled-id", "%prefix%Client probe is disabled: %probe%"),
            Map.entry("probe.manual-disabled", "%prefix%Client probe is not enabled for manual checks: %probe%"),
            Map.entry("probe.busy", "%prefix%A client probe is already running for that player."),
            Map.entry("probe.result", "%prefix%Client probe %player%: %status% (evidence=%evidence%, mods=%mods%, action=%action%)"),
            Map.entry("lunar.unavailable", "%prefix%Lunar integration is unavailable."),
            Map.entry("lunar.offline", "%prefix%Player is not online."),
            Map.entry("lunar.report.header", "%prefix%Lunar policy for %player%:"),
            Map.entry("lunar.report.integration", "%prefix%Apollo integration: %state%"),
            Map.entry("lunar.report.support", "%prefix%Lunar support: %support%"),
            Map.entry("lunar.report.policy", "%prefix%Minimap policy: %policy%"),
            Map.entry("lunar.report.state", "%prefix%Apollo state: %state%"),
            Map.entry("lunar.report.action", "%prefix%Last Apollo action: %state%"),
            Map.entry("kick.confirmed", "&c&lConnection Lost\n\n&fCheating detected.\n&7Detected: &f%mods%\n&7Reason: &f%reason%"));

    private final Map<String, String> values;

    private Messages(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public static Messages load(Path dataDirectory, Logger logger) {
        Map<String, String> values = new HashMap<>(DEFAULTS);
        if (dataDirectory == null) return new Messages(values);
        Path file = dataDirectory.resolve("messages.yml");
        if (!Files.isRegularFile(file)) return new Messages(values);
        try {
            for (String rawLine : Files.readAllLines(file)) {
                String line = rawLine.stripTrailing();
                if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                String[] pair = line.trim().split(":", 2);
                if (pair.length != 2) throw new IllegalArgumentException("Invalid messages.yml entry");
                String key = pair[0].trim();
                String value = pair[1].trim();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                value = value.replace("\\n", "\n");
                values.put(key, value);
            }
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "Unable to load messages.yml; using message defaults", exception);
            return new Messages(new HashMap<>(DEFAULTS));
        }
        return new Messages(values);
    }

    public String render(String key, Map<String, ?> placeholders) {
        String value = values.getOrDefault(key, DEFAULTS.getOrDefault(key, key));
        Map<String, Object> replacements = new HashMap<>();
        replacements.put("prefix", values.getOrDefault("prefix", DEFAULTS.get("prefix")));
        placeholders.forEach((name, replacement) -> replacements.put(name, replacement));
        for (Map.Entry<String, Object> entry : replacements.entrySet()) {
            value = value.replace("%" + entry.getKey() + "%", String.valueOf(entry.getValue()));
        }
        return value.replace('&', '\u00a7');
    }

    public String render(String key) {
        return render(key, Map.of());
    }
}
