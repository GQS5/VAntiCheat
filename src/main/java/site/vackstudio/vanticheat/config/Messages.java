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
            Map.entry("command.no-permission-admin", "%prefix%You do not have permission to manage trusted players."),
            Map.entry("command.no-permission-probe", "%prefix%You do not have permission to run client probes."),
            Map.entry("command.usage", "%prefix%Usage: /vac help"),
            Map.entry("command.reloaded", "%prefix%Configuration reloaded."),
            Map.entry("command.help.header", "%prefix%Commands:"),
            Map.entry("command.help.reload", "%prefix%/vac reload - reload VAntiCheat configuration"),
            Map.entry("command.help.trust", "%prefix%/vac trust <player> - trust a player"),
            Map.entry("command.help.trust-add", "%prefix%/vac trust add <player> - trust a player"),
            Map.entry("command.help.trust-remove", "%prefix%/vac trust remove <player> - remove trust"),
            Map.entry("command.help.trust-list", "%prefix%/vac trust list - list trusted players"),
            Map.entry("command.help.check", "%prefix%/vac check <player> - run a client probe"),
            Map.entry("command.help.probe", "%prefix%/vacprobe <player> - run a client probe alias"),
            Map.entry("command.trust.not-found", "%prefix%Player is not online or cached by the server."),
            Map.entry("command.trust.added", "%prefix%%player% is now trusted."),
            Map.entry("command.trust.exists", "%prefix%%player% is already trusted."),
            Map.entry("command.trust.removed", "%prefix%%player% is no longer trusted."),
            Map.entry("command.trust.not-trusted", "%prefix%%player% is not trusted."),
            Map.entry("command.trust.empty", "%prefix%No trusted players."),
            Map.entry("command.trust.header", "%prefix%Trusted players (%count%):"),
            Map.entry("command.trust.entry", "%prefix%- %player%"),
            Map.entry("probe.usage", "Usage: /vacprobe <player>"),
            Map.entry("probe.offline", "Player is not online"),
            Map.entry("probe.disabled", "%prefix%Client detection is disabled."),
            Map.entry("probe.start", "Starting client probe for %player%"),
            Map.entry("probe.result", "Client probe %player%: %status% (evidence=%evidence%, mods=%mods%)"),
            Map.entry("kick.confirmed", "Cheating detected."));

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
