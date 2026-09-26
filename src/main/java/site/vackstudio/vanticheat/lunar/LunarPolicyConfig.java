package site.vackstudio.vanticheat.lunar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server policy for Lunar Client Apollo integration.
 *
 * <p>Lunar Client itself is always allowed. Only the Lunar Minimap can be
 * disabled via Apollo, and only for players Apollo recognizes. This config
 * never produces kicks or cheat classifications.
 */
public record LunarPolicyConfig(boolean enabled, boolean minimapEnabled) {
    public static LunarPolicyConfig defaults() {
        return new LunarPolicyConfig(true, true);
    }

    public static LunarPolicyConfig disabled() {
        return new LunarPolicyConfig(false, false);
    }

    public static LunarPolicyConfig load(Path dataDirectory, Logger logger) {
        if (dataDirectory == null) return disabled();
        Path file = dataDirectory.resolve("config.yml");
        if (!Files.isRegularFile(file)) return defaults();
        try {
            return parse(Files.readString(file));
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING,
                    "Unable to load lunar policy from config.yml; lunar integration disabled", exception);
            return disabled();
        }
    }

    static LunarPolicyConfig parse(String content) {
        boolean enabled = true;
        boolean minimapEnabled = true;
        boolean inLunar = false;
        boolean inMinimap = false;
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.stripTrailing();
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length();
            String trimmed = line.trim();
            if (indent == 0) {
                inLunar = trimmed.equals("lunar:");
                inMinimap = false;
                continue;
            }
            if (!inLunar) continue;
            if (indent == 2) {
                if (trimmed.equals("minimap:")) {
                    inMinimap = true;
                    continue;
                }
                inMinimap = false;
                String[] pair = pair(trimmed, "lunar");
                if (pair[0].equals("enabled")) enabled = bool(pair[1], "lunar.enabled");
                continue;
            }
            if (indent >= 4 && inMinimap) {
                String[] pair = pair(trimmed, "lunar.minimap");
                switch (pair[0]) {
                    case "enabled" -> minimapEnabled = bool(pair[1], "lunar.minimap.enabled");
                    case "action" -> {
                        if (!pair[1].equalsIgnoreCase("DISABLE")) {
                            throw new IllegalArgumentException(
                                    "Unsupported value at lunar.minimap.action: '" + pair[1]
                                            + "'; only DISABLE is supported");
                        }
                    }
                    default -> { }
                }
            }
        }
        return new LunarPolicyConfig(enabled, minimapEnabled);
    }

    private static String[] pair(String value, String path) {
        String[] pair = value.split(":", 2);
        if (pair.length != 2) throw new IllegalArgumentException("Invalid config entry at " + path);
        return new String[]{pair[0].trim(), unquote(pair[1].trim())};
    }

    private static boolean bool(String value, String path) {
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException(
                "Expected boolean at " + path + " but was '" + value + "'");
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
